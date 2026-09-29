package com.eter.rutacree.driver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.rutacree.api.ApiException
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.DriverSession
import com.eter.rutacree.settings.KeyValueStore
import com.eter.rutacree.api.DriverSocketMessage
import com.eter.rutacree.api.RutaCreeApi
import com.eter.rutacree.api.TrackerStatus
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.DirectionStep
import com.eter.rutacree.transit.Directions
import com.eter.rutacree.transit.DirectionsClient
import com.eter.rutacree.transit.GeoPoint
import com.eter.rutacree.transit.Incident
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.transit.RampRequest
import com.eter.rutacree.transit.RouteRepository
import com.eter.rutacree.transit.SimulatedDispatch
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.transit.TransitRoute
import com.eter.rutacree.transit.distanceMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

data class DriverShift(val driverName: String, val busNumber: String, val route: TransitRoute)

enum class FinalLocation(val wire: String) { Terminal("terminal"), Workshop("workshop"), Depot("depot") }

enum class ShiftEnd { Completed, Emergency, ClosedByServer }

enum class StartError { Network, Rejected }

/** Guidance while the bus is away from its route: how to reach [targetStop] by street. */
data class Approach(
    val target: GeoPoint,
    val targetStop: Stop?,
    val remainingMeters: Double,
    /** Null until a street path arrives (then [path] is a straight line to the target). */
    val remainingSeconds: Double?,
    val nextStep: DirectionStep?,
    val nextStepMeters: Double?,
    /** What is left to drive, from the bus to the target. */
    val path: List<GeoPoint>,
    val byStreet: Boolean,
)

data class DriverUiState(
    /** The signed-in driver (the login decided this person is a driver). */
    val driverName: String = "",
    val routes: List<TransitRoute> = emptyList(),
    val busNumber: String = "",
    val selectedRouteId: String? = null,
    val starting: Boolean = false,
    val startError: StartError? = null,
    val startErrorMessage: String? = null,
    val shift: DriverShift? = null,
    /** The bus position; null on shift until the phone gets a GPS fix. */
    val position: GeoPoint? = null,
    /** Set while the bus is away from the route. */
    val approach: Approach? = null,
    val nextStops: List<Stop> = emptyList(),
    /** Whether the last position report reached the server (always true in simulation). */
    val reporting: Boolean = true,
    /** Pending ramp requests for this route, oldest first; the first one is on screen. */
    val rampQueue: List<RampRequest> = emptyList(),
    val lastShiftEnd: ShiftEnd? = null,
    /** Set when the bus position comes from Traccar Client on this phone. */
    val tracker: TrackerStatus? = null,
) {
    val rampAlert: RampRequest? get() = rampQueue.firstOrNull()

    val canStart: Boolean
        get() = !starting && selectedRouteId != null && busNumber.isNotBlank()
}

private const val REPORT_INTERVAL_MS = 3_000L
private const val SIM_TICK_MS = 2_000L
private const val SIM_SPEED_KMH = 22.0
private const val SIM_TIME_SCALE = 3.0
private const val SIM_RAMP_REQUEST_DELAY_MS = 20_000L
private const val NEXT_STOPS = 3
private const val TRACKER_POLL_MS = 10_000L

// Hysteresis, so GPS noise near the line doesn't flip between "go to the route" and "on route".
private const val OFF_ROUTE_M = 80.0
private const val ON_ROUTE_M = 35.0
/** Leaving the suggested street path by this much asks for a new one. */
private const val OFF_PATH_M = 60.0
private val DIRECTIONS_MIN_INTERVAL = 15.seconds

private const val LAST_BUS_KEY = "driver.lastBus"
private const val LAST_ROUTE_KEY = "driver.lastRoute"

/**
 * [account] comes from the single sign-in; its token only opens shifts. [onSessionExpired] sends the
 * app back to the login screen when the server rejects that token. [loadRoutes] is replaced in tests.
 */
class DriverViewModel(
    private val config: AppConfig,
    private val account: DriverAccount,
    private val store: KeyValueStore,
    private val onSessionExpired: () -> Unit,
    private val loadRoutes: suspend (RutaCreeApi?) -> List<TransitRoute> = RouteRepository::load,
) : ViewModel() {

    private val api = config.apiBaseUrl.takeIf { it.isNotBlank() }?.let(::RutaCreeApi)

    // Drivers usually take the same bus and route every day: prefill the last ones.
    private val _state = MutableStateFlow(
        DriverUiState(driverName = account.driver.name, busNumber = store.get(LAST_BUS_KEY).orEmpty())
    )
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    /** Kept only in memory: a stolen phone at rest holds no valid credential. */
    private var session: DriverSession? = null
    private var gps: GeoPoint? = null
    private var speedKmh = 0.0
    private var shiftJobs = emptyList<Job>()

    private val directionsClient = DirectionsClient()
    private var directions: Directions? = null
    private var directionsTarget: GeoPoint? = null
    private var directionsJob: Job? = null
    private var lastDirectionsRequest: TimeMark? = null

    init {
        viewModelScope.launch {
            val routes = runCatching { loadRoutes(api) }.getOrDefault(emptyList())
            val lastRoute = store.get(LAST_ROUTE_KEY)?.takeIf { id -> routes.any { it.id == id } }
            _state.update { it.copy(routes = routes, selectedRouteId = lastRoute ?: routes.firstOrNull()?.id) }
        }
        if (api == null) {
            viewModelScope.launch {
                SimulatedDispatch.rampRequests.collect { request ->
                    _state.update { s -> if (s.shift?.route?.id == request.routeId) s.enqueue(listOf(request)) else s }
                }
            }
        }
    }

    fun selectRoute(id: String) = _state.update { it.copy(selectedRouteId = id) }
    fun updateBusNumber(value: String) =
        _state.update { s -> s.copy(busNumber = value.filter { it.isLetterOrDigit() }.take(8), startError = null) }

    fun updateLocation(location: GeoPoint?, speed: Double = 0.0) {
        gps = location
        speedKmh = speed
        if (location != null) setPosition(location)
    }

    fun startShift() {
        val s = _state.value
        if (!s.canStart) return
        val route = s.routes.first { it.id == s.selectedRouteId }
        store.put(LAST_BUS_KEY, s.busNumber)
        store.put(LAST_ROUTE_KEY, route.id)
        if (api == null) {
            beginShift(DriverShift(account.driver.name, s.busNumber, route))
            return
        }
        _state.update { it.copy(starting = true, startError = null, startErrorMessage = null) }
        viewModelScope.launch {
            try {
                val newSession = api.startShift(account.token, s.busNumber, route.id)
                session = newSession
                beginShift(DriverShift(newSession.driverName, newSession.busNumber, route))
            } catch (e: ApiException) {
                if (e.unauthorized) {
                    // The login expired or an admin deactivated the driver.
                    _state.update { it.copy(starting = false) }
                    return@launch onSessionExpired()
                }
                val error = if (e.status == null) StartError.Network else StartError.Rejected
                _state.update { it.copy(starting = false, startError = error, startErrorMessage = e.message) }
            }
        }
    }

    private fun beginShift(shift: DriverShift) {
        _state.update {
            it.copy(shift = shift, starting = false, lastShiftEnd = null, rampQueue = emptyList(), reporting = true)
        }
        val jobs = mutableListOf<Job>()
        val fix = gps
        // With a server, positions only ever come from GPS: nothing invented is reported to riders.
        if (fix != null) setPosition(fix)
        else if (api == null) jobs += viewModelScope.launch { simulateDriving(shift.route) }
        val current = session
        if (api != null && current != null) {
            // Also with Traccar: the server ignores these while Traccar Client reports, so they are the fallback.
            jobs += viewModelScope.launch { reportLoop(api, current) }
            jobs += viewModelScope.launch { listenForRamps(api, current) }
            if (current.usesTraccar) {
                _state.update {
                    it.copy(tracker = TrackerStatus("traccar", current.trackerDeviceId, current.trackerUrl))
                }
                jobs += viewModelScope.launch { pollTracker(api, current) }
            }
        } else {
            // Demo: a rider asks for the ramp shortly after the shift starts.
            jobs += viewModelScope.launch {
                delay(SIM_RAMP_REQUEST_DELAY_MS)
                _state.value.nextStops.firstOrNull { it.accessible }?.let {
                    SimulatedDispatch.requestRamp(shift.route.id, it.id, it.name)
                }
            }
        }
        shiftJobs = jobs
    }

    fun acknowledgeRamp() {
        val request = _state.value.rampAlert ?: return
        _state.update { s -> s.copy(rampQueue = s.rampQueue.drop(1)) }
        val current = session ?: return
        viewModelScope.launch {
            try {
                api?.acknowledgeRamp(current, request.id)
            } catch (e: ApiException) {
                if (e.unauthorized) stopShift(ShiftEnd.ClosedByServer)
            }
        }
    }

    /** Crash or mechanical failure: alerts riders on the route and cancels the shift immediately. */
    fun reportEmergency(type: IncidentType) {
        val shift = _state.value.shift ?: return
        val current = session
        if (api != null && current != null) {
            // Fire-and-forget on the ViewModel scope so it completes even after the shift UI closes.
            viewModelScope.launch {
                runCatching { api.reportIncident(current, accident = type == IncidentType.Accident) }
            }
        } else {
            SimulatedDispatch.reportIncident(
                Incident("driver-${shift.busNumber}", shift.busNumber.hashCode().toLong(), shift.busNumber, shift.route.id, type)
            )
        }
        stopShift(ShiftEnd.Emergency)
    }

    fun endShift(finalLocation: FinalLocation) {
        val current = session
        if (api != null && current != null) {
            viewModelScope.launch { runCatching { api.endShift(current, finalLocation.wire) } }
        }
        stopShift(ShiftEnd.Completed)
    }

    private fun stopShift(end: ShiftEnd) {
        shiftJobs.forEach { it.cancel() }
        shiftJobs = emptyList()
        session = null
        clearDirections()
        _state.update {
            it.copy(
                shift = null, nextStops = emptyList(), rampQueue = emptyList(), lastShiftEnd = end, tracker = null,
                approach = null,
            )
        }
    }

    private fun setPosition(point: GeoPoint) {
        val s = _state.value
        val route = s.shift?.route ?: return _state.update { it.copy(position = point) }
        val onRoute = route.geometry.project(point)
        val nextStops = route.stops.filter { it.alongMeters > onRoute.alongMeters + 10 }.take(NEXT_STOPS)
        val away = onRoute.offsetMeters > if (s.approach != null) ON_ROUTE_M else OFF_ROUTE_M
        val approach = if (away) approachFrom(point, route, onRoute.alongMeters) else null
        if (approach == null) clearDirections()
        _state.update { it.copy(position = point, nextStops = nextStops, approach = approach) }
    }

    /**
     * Joins the route at the next stop ahead of its closest point, so the bus arrives going the right
     * way and where riders wait. Past the last stop it starts over from the terminal.
     */
    private fun approachFrom(point: GeoPoint, route: TransitRoute, alongMeters: Double): Approach {
        val stop = route.stops.firstOrNull { it.alongMeters >= alongMeters } ?: route.stops.firstOrNull()
        val target = stop?.point ?: route.geometry.pointAt(alongMeters)
        val current = directions?.takeIf { directionsTarget == target }
        val progress = current?.path?.project(point)
        if (current == null || progress!!.offsetMeters > OFF_PATH_M) requestDirections(point, target)
        if (current == null || progress == null) {
            return Approach(
                target, stop, distanceMeters(point, target), null, null, null, listOf(point, target), byStreet = false,
            )
        }
        val along = progress.alongMeters
        val length = current.path.lengthMeters
        val next = current.stepAlong.indexOfFirst { it > along + 8 }.takeIf { it >= 0 }
        return Approach(
            target = target,
            targetStop = stop,
            remainingMeters = length - along,
            remainingSeconds = if (length > 0) current.durationSeconds * (length - along) / length else 0.0,
            nextStep = next?.let { current.steps[it] },
            nextStepMeters = next?.let { current.stepAlong[it] - along },
            path = listOf(point) + current.path.pointsAfter(along),
            byStreet = true,
        )
    }

    private fun requestDirections(from: GeoPoint, to: GeoPoint) {
        if (directionsJob?.isActive == true) return
        if (lastDirectionsRequest?.let { it.elapsedNow() < DIRECTIONS_MIN_INTERVAL } == true) return
        lastDirectionsRequest = TimeSource.Monotonic.markNow()
        directionsJob = viewModelScope.launch {
            val result = directionsClient.driving(from, to) ?: return@launch
            directions = result
            directionsTarget = to
            _state.value.position?.let { if (_state.value.approach != null) setPosition(it) }
        }
    }

    private fun clearDirections() {
        directionsJob?.cancel()
        directions = null
        directionsTarget = null
        lastDirectionsRequest = null
    }

    private suspend fun simulateDriving(route: TransitRoute) {
        var along = 0.0
        speedKmh = SIM_SPEED_KMH
        while (gps == null) {
            setPosition(route.geometry.pointAt(along))
            delay(SIM_TICK_MS)
            along += SIM_SPEED_KMH / 3.6 * (SIM_TICK_MS / 1000.0) * SIM_TIME_SCALE
            if (along > route.geometry.lengthMeters) along = 0.0
        }
    }

    private suspend fun reportLoop(api: RutaCreeApi, current: DriverSession) {
        while (true) {
            val position = _state.value.position
            if (position != null) {
                val ok = try {
                    api.reportPosition(current, position, speedKmh)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiException) {
                    // 401: the server closed this shift (expired, replaced or ended by an admin).
                    if (e.unauthorized) return stopShift(ShiftEnd.ClosedByServer)
                    false
                }
                _state.update { it.copy(reporting = ok) }
            }
            delay(REPORT_INTERVAL_MS)
        }
    }

    private suspend fun pollTracker(api: RutaCreeApi, current: DriverSession) {
        while (true) {
            try {
                val status = api.trackerStatus(current)
                _state.update { it.copy(tracker = status) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.unauthorized) return stopShift(ShiftEnd.ClosedByServer)
            }
            delay(TRACKER_POLL_MS)
        }
    }

    private suspend fun listenForRamps(api: RutaCreeApi, current: DriverSession) {
        api.driverSocket(current).collect { message ->
            when (message) {
                is DriverSocketMessage.Requests -> _state.update { it.enqueue(message.requests) }
                is DriverSocketMessage.Acknowledged ->
                    _state.update { s -> s.copy(rampQueue = s.rampQueue.filterNot { it.id in message.ids }) }
                is DriverSocketMessage.Connection -> Unit
            }
        }
    }

    private fun DriverUiState.enqueue(requests: List<RampRequest>): DriverUiState {
        val known = rampQueue.map { it.id }.toSet()
        return copy(rampQueue = rampQueue + requests.filter { it.id !in known })
    }
}
