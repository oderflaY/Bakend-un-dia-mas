package com.eter.rutacree.transit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.rutacree.api.ApiException
import com.eter.rutacree.api.RampStatus
import com.eter.rutacree.api.ReportKind
import com.eter.rutacree.api.RutaCreeApi
import com.eter.rutacree.platform.currentMinuteOfDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import org.jetbrains.compose.resources.ExperimentalResourceApi
import rutacree.shared.generated.resources.Res

data class AppConfig(
    val mapboxAccessToken: String = "",
    val mapboxStyleUrl: String = "",
    val mapboxDarkStyleUrl: String = "",
    /** RutaCree backend, e.g. `http://192.168.1.10:8080`; blank runs the app in simulation mode. */
    val apiBaseUrl: String = "",
) {
    fun styleUrl(dark: Boolean): String =
        if (dark) mapboxDarkStyleUrl.ifBlank { "mapbox://styles/mapbox/dark-v11" }
        else mapboxStyleUrl.ifBlank { "mapbox://styles/mapbox/light-v11" }

    val isLive: Boolean get() = apiBaseUrl.isNotBlank()
}

object RouteRepository {
    private val files = listOf("files/routes/ruta-1.geojson", "files/routes/ruta-2.geojson")

    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadBundled(): List<TransitRoute> = withContext(Dispatchers.Default) {
        files.map { parseRouteGeoJson(Res.readBytes(it).decodeToString()) }
    }

    /** Server routes when a backend is configured; the bundled copy if it is unreachable. */
    suspend fun load(api: RutaCreeApi?): List<TransitRoute> =
        api?.let { runCatching { it.routes() }.getOrNull()?.takeIf { routes -> routes.isNotEmpty() } } ?: loadBundled()
}

enum class RampFlow { Idle, Sending, WaitingDriver, Acknowledged, Failed }

data class RampUiState(val flow: RampFlow = RampFlow.Idle, val driversNotified: Int = 0)

enum class SendState { Idle, Sending, Sent, Failed }

data class TripUiState(val route: TransitRoute, val progress: TripProgress?)

data class TransitUiState(
    val loading: Boolean = true,
    val routes: List<TransitRoute> = emptyList(),
    val route: TransitRoute? = null,
    val stop: Stop? = null,
    /** Buses of the selected route with a working ramp; the only ones the rider can board. */
    val buses: List<BusOnRoute> = emptyList(),
    val arrival: Arrival? = null,
    /** Stop geofence around the rider; null while their location is unknown. */
    val geofence: Geofence? = null,
    /** The rider's GPS position, when available. */
    val userLocation: GeoPoint? = null,
    /** How to walk from [userLocation] to [stop]; null without GPS or when the stop is too far. */
    val walk: WalkGuide? = null,
    val nowMinuteOfDay: Int = 0,
    val trip: TripUiState? = null,
    val incident: Incident? = null,
    val ramp: RampUiState = RampUiState(),
    val report: SendState = SendState.Idle,
    val live: Boolean = false,
    /** False while the live socket is down; the map keeps the last known positions. */
    val online: Boolean = true,
    val loadFailed: Boolean = false,
)

sealed interface TripAlert {
    data class PrepareToExit(val stop: Stop) : TripAlert
    data class Arrived(val stop: Stop) : TripAlert
}

private data class Inputs(
    val routes: List<TransitRoute> = emptyList(),
    val loadFailed: Boolean = false,
    val routeId: String? = null,
    val stopId: String? = null,
    val stopChosenByUser: Boolean = false,
    val positions: List<BusPosition> = emptyList(),
    val speeds: Map<Long, Double> = emptyMap(),
    val online: Boolean = true,
    val location: GeoPoint? = null,
    /** Street path on foot to [walkTarget], once the router answered. */
    val walkDirections: Directions? = null,
    val walkTarget: GeoPoint? = null,
    val nowMinute: Int = 0,
    val trip: Trip? = null,
    val incident: Incident? = null,
    val ramp: RampUiState = RampUiState(),
    val report: SendState = SendState.Idle,
)

private const val DEFAULT_ROUTE_ID = "ruta-2"
private const val DEFAULT_STOP_NAME = "20 de Noviembre"
private const val SPEED_SMOOTHING = 0.3
private const val CLOCK_TICK_MS = 30_000L
private const val RAMP_POLL_MS = 3_000L
private const val RAMP_POLL_LIMIT = 100
private const val SIM_ACK_DELAY_MS = 2_500L
private val WALK_DIRECTIONS_MIN_INTERVAL = 20.seconds

class TransitViewModel(private val config: AppConfig) : ViewModel() {

    private val api = config.apiBaseUrl.takeIf { it.isNotBlank() }?.let(::RutaCreeApi)
    private val inputs = MutableStateFlow(Inputs(nowMinute = currentMinuteOfDay()))
    private var rampJob: Job? = null
    private val directionsClient = DirectionsClient()
    private var walkJob: Job? = null
    private var lastWalkRequest: Pair<GeoPoint, TimeMark>? = null

    private val _tripAlerts = MutableSharedFlow<TripAlert>(extraBufferCapacity = 4)
    val tripAlerts: SharedFlow<TripAlert> = _tripAlerts.asSharedFlow()

    val state: StateFlow<TransitUiState> =
        inputs.map { it.toUiState() }.stateIn(viewModelScope, SharingStarted.Eagerly, TransitUiState())

    init {
        viewModelScope.launch {
            val loaded = try {
                RouteRepository.load(api)
            } catch (_: Exception) {
                inputs.update { it.copy(loadFailed = true) }
                return@launch
            }
            inputs.update { it.copy(routes = loaded) }
            applyRoute(loaded.firstOrNull { it.id == DEFAULT_ROUTE_ID }?.id ?: loaded.first().id, byUser = false)
            nearestStopTo(inputs.value.location)
            val feed = if (api != null) TraccarSocketFeed(api.publicSocketUrl) else SimulatedFeed(loaded)
            feed.updates.collect(::onFeedUpdate)
        }
        viewModelScope.launch {
            while (true) {
                delay(CLOCK_TICK_MS)
                inputs.update { it.copy(nowMinute = currentMinuteOfDay()) }
            }
        }
        viewModelScope.launch {
            var lastStage: TripStage? = null
            state.collect { s ->
                val progress = s.trip?.progress
                val stage = progress?.stage
                if (stage != lastStage && progress != null) {
                    when (stage) {
                        TripStage.PrepareToExit -> _tripAlerts.emit(TripAlert.PrepareToExit(progress.destination))
                        TripStage.Arrived -> _tripAlerts.emit(TripAlert.Arrived(progress.destination))
                        else -> Unit
                    }
                }
                lastStage = stage
            }
        }
        viewModelScope.launch {
            // Ask for a street path whenever the guide is still a straight line (new stop, or rider left the path).
            state.collect { s ->
                val walk = s.walk ?: return@collect
                val from = s.userLocation ?: return@collect
                if (!walk.byStreet && !walk.arrived) requestWalkDirections(from, walk.stop.point)
            }
        }
    }

    private fun requestWalkDirections(from: GeoPoint, to: GeoPoint) {
        if (walkJob?.isActive == true) return
        val last = lastWalkRequest
        // Same stop: the public router is shared, so ask again at most every few seconds.
        if (last != null && last.first == to && last.second.elapsedNow() < WALK_DIRECTIONS_MIN_INTERVAL) return
        lastWalkRequest = to to TimeSource.Monotonic.markNow()
        walkJob = viewModelScope.launch {
            val result = directionsClient.walking(from, to) ?: return@launch
            inputs.update { it.copy(walkDirections = result, walkTarget = to) }
        }
    }

    private fun onFeedUpdate(update: FeedUpdate) {
        when (update) {
            is FeedUpdate.Positions -> inputs.update { current ->
                // Instantaneous GPS speed drops to 0 at every stop; a moving average gives stable ETAs.
                val speeds = current.speeds.toMutableMap()
                update.buses.forEach { bus ->
                    val previous = speeds[bus.deviceId] ?: bus.speedKmh
                    speeds[bus.deviceId] = previous + SPEED_SMOOTHING * (bus.speedKmh - previous)
                }
                current.copy(positions = update.buses, speeds = speeds)
            }
            is FeedUpdate.Connection -> inputs.update { it.copy(online = update.online) }
            is FeedUpdate.IncidentReported -> inputs.update { current ->
                val relevant = update.incident.routeId == null ||
                    update.incident.routeId == current.routeId ||
                    update.incident.routeId == current.trip?.routeId
                if (relevant) current.copy(incident = update.incident) else current
            }
        }
    }

    fun selectRoute(routeId: String) = applyRoute(routeId, byUser = true)

    private fun applyRoute(routeId: String, byUser: Boolean) = inputs.update { current ->
        val route = current.routes.firstOrNull { it.id == routeId } ?: return@update current
        val currentName = current.routes.flatMap { it.stops }.firstOrNull { it.id == current.stopId }?.name
        val accessible = route.stops.filter { it.accessible }
        val stop = accessible.firstOrNull { it.name == currentName }
            ?: accessible.firstOrNull { DEFAULT_STOP_NAME in it.name }
            ?: accessible.getOrNull(accessible.size / 2)
        current.copy(routeId = routeId, stopId = stop?.id, stopChosenByUser = current.stopChosenByUser || byUser)
    }

    fun selectStop(stopId: String) = inputs.update { it.copy(stopId = stopId, stopChosenByUser = true) }

    /** GPS fix from the device. The first fix centers the app on the nearest accessible stop. */
    fun updateUserLocation(location: GeoPoint?) {
        val first = inputs.value.location == null && location != null
        inputs.update { it.copy(location = location) }
        if (first) nearestStopTo(location)
    }

    private fun nearestStopTo(location: GeoPoint?) {
        if (location == null) return
        inputs.update { current ->
            if (current.stopChosenByUser) return@update current
            val best = current.routes
                .flatMap { route -> route.stops.filter { it.accessible }.map { route to it } }
                .minByOrNull { (_, stop) -> distanceMeters(location, stop.point) }
                ?: return@update current
            current.copy(routeId = best.first.id, stopId = best.second.id)
        }
    }

    /** Sends the ramp request for the selected stop and follows it until a driver acknowledges it. */
    fun requestRamp() {
        val s = state.value
        val route = s.route ?: return
        val stop = s.stop ?: return
        rampJob?.cancel()
        setRamp(RampUiState(RampFlow.Sending))
        rampJob = viewModelScope.launch {
            if (api == null) {
                SimulatedDispatch.requestRamp(route.id, stop.id, stop.name)
                delay(SIM_ACK_DELAY_MS)
                setRamp(RampUiState(RampFlow.Acknowledged))
                return@launch
            }
            try {
                val ticket = api.requestRamp(route.id, stop.id)
                setRamp(RampUiState(RampFlow.WaitingDriver, ticket.driversNotified))
                repeat(RAMP_POLL_LIMIT) {
                    when (api.rampStatus(ticket.id)) {
                        RampStatus.Acknowledged -> return@launch setRamp(RampUiState(RampFlow.Acknowledged))
                        RampStatus.Expired -> return@launch setRamp(RampUiState(RampFlow.Failed))
                        RampStatus.Pending -> delay(RAMP_POLL_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: ApiException) {
                setRamp(RampUiState(RampFlow.Failed))
            }
        }
    }

    /** Stops following the request on this phone (the backend has no cancel endpoint yet). */
    fun cancelRamp() {
        rampJob?.cancel()
        setRamp(RampUiState())
    }

    private fun setRamp(ramp: RampUiState) = inputs.update { it.copy(ramp = ramp) }

    fun sendReport(kind: ReportKind, comment: String) {
        val s = state.value
        inputs.update { it.copy(report = SendState.Sending) }
        viewModelScope.launch {
            val ok = if (api == null) {
                delay(600)
                true
            } else {
                try {
                    api.sendReport(kind, s.route?.id, s.arrival?.bus?.position?.deviceId, comment)
                    true
                } catch (_: ApiException) {
                    false
                }
            }
            inputs.update { it.copy(report = if (ok) SendState.Sent else SendState.Failed) }
        }
    }

    fun resetReport() = inputs.update { it.copy(report = SendState.Idle) }

    fun startTrip(destinationStopId: String) {
        val s = state.value
        val route = s.route ?: return
        inputs.update {
            it.copy(
                trip = Trip(
                    routeId = route.id,
                    boardingStopId = s.stop?.id,
                    destinationStopId = destinationStopId,
                    boardedBusId = s.arrival?.bus?.position?.deviceId,
                )
            )
        }
    }

    fun endTrip() = inputs.update { it.copy(trip = null) }

    fun dismissIncident() = inputs.update { it.copy(incident = null) }

    /** Switches to another route serving the rider's stop (or any other route) after an incident. */
    fun showAlternativeRoutes() {
        val current = inputs.value
        val affected = current.incident?.routeId ?: current.routeId
        val stopName = current.routes.flatMap { it.stops }.firstOrNull { it.id == current.stopId }?.name
        val others = current.routes.filter { it.id != affected }
        val alternative = others.firstOrNull { route -> route.stops.any { it.name == stopName && it.accessible } }
            ?: others.firstOrNull()
        inputs.update { it.copy(incident = null) }
        alternative?.let { selectRoute(it.id) }
    }

    private fun Inputs.toUiState(): TransitUiState {
        val route = routes.firstOrNull { it.id == routeId }
        val stop = route?.stops?.firstOrNull { it.id == stopId }
        // Without GPS in simulation mode, pretend the rider is standing at their chosen stop.
        val riderAt = location ?: stop?.point?.takeIf { !config.isLive }
        val walk = if (location != null && stop != null) {
            walkGuide(location, stop, walkDirections?.takeIf { walkTarget == stop.point })
        } else null
        val buses = if (route == null) emptyList() else positions
            .filter { it.routeId == route.id && it.rampOperational }
            .mapNotNull { route.place(it, speeds[it.deviceId] ?: it.speedKmh) }
        return TransitUiState(
            loading = routes.isEmpty() && !loadFailed,
            routes = routes,
            route = route,
            stop = stop,
            buses = buses,
            arrival = if (route != null && stop != null) route.nextArrival(stop, buses) else null,
            geofence = if (route != null && riderAt != null) {
                nearestStop(riderAt, route.stops.filter { it.accessible })
            } else null,
            userLocation = location,
            walk = walk,
            nowMinuteOfDay = nowMinute,
            trip = trip?.let { toTripUi(it) },
            incident = incident,
            ramp = ramp,
            report = report,
            live = config.isLive,
            online = online,
            loadFailed = loadFailed,
        )
    }

    private fun Inputs.toTripUi(trip: Trip): TripUiState? {
        val route = routes.firstOrNull { it.id == trip.routeId } ?: return null
        val destination = route.stops.firstOrNull { it.id == trip.destinationStopId } ?: return null
        // Rider position: the phone's GPS, or (simulation without GPS) the bus they boarded.
        val rider = location ?: positions.firstOrNull { it.deviceId == trip.boardedBusId }?.point
        val boarding = route.stops.firstOrNull { it.id == trip.boardingStopId }
        return TripUiState(route, rider?.let { route.tripProgress(boarding, destination, it) })
    }
}
