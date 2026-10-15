package com.eter.rutacree.driver

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eter.rutacree.api.ApiException
import com.eter.rutacree.api.DriverAccount
import com.eter.rutacree.api.DriverSession
import com.eter.rutacree.api.DriverSocketMessage
import com.eter.rutacree.api.Passengers
import com.eter.rutacree.api.RutaCreeApi
import com.eter.rutacree.platform.DeviceLocation
import com.eter.rutacree.platform.currentMinuteOfDay
import com.eter.rutacree.settings.KeyValueStore
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.Incident
import com.eter.rutacree.transit.IncidentType
import com.eter.rutacree.transit.RampRequest
import com.eter.rutacree.transit.RouteRepository
import com.eter.rutacree.transit.SimulatedDispatch
import com.eter.rutacree.transit.TransitRoute
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** [startMinute] is the phone's local time of day when the route began, for the WhatsApp reports. */
data class DriverShift(
    val driverName: String,
    val busNumber: String,
    val route: TransitRoute,
    val startMinute: Int,
    val passengers: Passengers,
    /** Already reported in this shift: the bus stays marked without ramp until the workshop fixes it. */
    val rampFailureReported: Boolean = false,
) {
    /** "07:05", as written in the WhatsApp reports. */
    val startTime: String get() = WhatsAppReports.clock(startMinute)
}

enum class FinalLocation(val wire: String) { Terminal("terminal"), Workshop("workshop"), Depot("depot") }

enum class ShiftEnd { Completed, Emergency, ClosedByServer }

enum class StartError { Network, Rejected }

enum class PassengerKind { Men, Women, Disability, Elderly, Pregnant, Companions }

/** The phone's GPS sent as the bus position (demo mode, [AppConfig.driverPhoneTracking]). */
enum class PhoneGps {
    /** Not used: no route open, or the bus reports with its own GPS. */
    Off,
    /** Route open, waiting for the first fix. */
    Searching,
    /** The bus is on the map with this phone's position. */
    Sending,
    /** No fix for a while (indoors, GPS off or permission denied). */
    NoSignal,
    /** Fixes arrive but the server can't be reached. */
    NoConnection,
}

data class DriverUiState(
    /** The signed-in driver (the login decided this person is a driver). */
    val driverName: String = "",
    val routes: List<TransitRoute> = emptyList(),
    val busNumber: String = "",
    val selectedRouteId: String? = null,
    /** Who boards at the start of the route; asked before starting it. */
    val passengers: Passengers = Passengers(),
    val starting: Boolean = false,
    val startError: StartError? = null,
    val startErrorMessage: String? = null,
    val shift: DriverShift? = null,
    /** Pending ramp requests for this route, oldest first; the first one is on screen. */
    val rampQueue: List<RampRequest> = emptyList(),
    val lastShiftEnd: ShiftEnd? = null,
    /** A report waiting to be opened in WhatsApp; the screen opens it and calls [DriverViewModel.whatsAppOpened]. */
    val whatsApp: WhatsAppMessage? = null,
    /** The last report sent, so the driver can open it again if WhatsApp was closed without sending. */
    val lastWhatsApp: WhatsAppMessage? = null,
    val phoneGps: PhoneGps = PhoneGps.Off,
) {
    val rampAlert: RampRequest? get() = rampQueue.firstOrNull()

    val canStart: Boolean
        get() = !starting && selectedRouteId != null && busNumber.isNotBlank()
}

private const val SIM_RAMP_REQUEST_DELAY_MS = 20_000L

/** How often the phone's position goes to the server (the server accepts at most one per second). */
private const val POSITION_EVERY_MS = 3_000L
/** A fix older than this no longer counts as the bus position. */
private const val FIX_MAX_AGE_MS = 20_000L

private const val LAST_BUS_KEY = "driver.lastBus"
private const val LAST_ROUTE_KEY = "driver.lastRoute"

/**
 * The driver starts and ends the route, counts who is on board, reports failures and sees ramp
 * requests. Every report is saved on the server and also opened in WhatsApp for dispatch. The bus has
 * its own GPS in Traccar; for demos ([AppConfig.driverPhoneTracking]) the phone's GPS stands in for it.
 *
 * [account] comes from the single sign-in; its token only opens shifts. [onSessionExpired] sends the
 * app back to the login screen when the server rejects that token. [loadRoutes] and [clock] (minute of
 * the day) are replaced in tests.
 */
class DriverViewModel(
    private val config: AppConfig,
    private val account: DriverAccount,
    private val store: KeyValueStore,
    private val onSessionExpired: () -> Unit,
    private val loadRoutes: suspend (RutaCreeApi?) -> List<TransitRoute> = RouteRepository::load,
    private val clock: () -> Int = ::currentMinuteOfDay,
) : ViewModel() {

    private val api = config.apiBaseUrl.takeIf { it.isNotBlank() }?.let(::RutaCreeApi)

    // Drivers usually take the same bus and route every day: prefill the last ones.
    private val _state = MutableStateFlow(
        DriverUiState(driverName = account.driver.name, busNumber = store.get(LAST_BUS_KEY).orEmpty())
    )
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    /** Kept only in memory: a stolen phone at rest holds no valid credential. */
    private var session: DriverSession? = null
    private var whatsAppPhone = ""
    private var nextMessageId = 1L
    private var shiftJobs = emptyList<Job>()
    private var lastFix: DeviceLocation? = null
    private var lastFixAt: TimeMark? = null

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

    /** Every GPS fix of the phone; only used while a route is open in demo mode. */
    fun onPhoneLocation(fix: DeviceLocation?) {
        if (fix == null) return
        lastFix = fix
        lastFixAt = TimeSource.Monotonic.markNow()
    }
    fun updateBusNumber(value: String) =
        _state.update { s -> s.copy(busNumber = value.filter { it.isLetterOrDigit() || it == '-' }.take(16), startError = null) }

    /** +1 / -1 from the counters on the start screen; never below 0 nor above [MAX_PASSENGERS]. */
    fun changePassengers(kind: PassengerKind, delta: Int) = _state.update { s ->
        val p = s.passengers
        fun bump(n: Int) = (n + delta).coerceIn(0, MAX_PASSENGERS)
        s.copy(
            passengers = when (kind) {
                PassengerKind.Men -> p.copy(men = bump(p.men))
                PassengerKind.Women -> p.copy(women = bump(p.women))
                PassengerKind.Disability -> p.copy(disability = bump(p.disability))
                PassengerKind.Elderly -> p.copy(elderly = bump(p.elderly))
                PassengerKind.Pregnant -> p.copy(pregnant = bump(p.pregnant))
                PassengerKind.Companions -> p.copy(companions = bump(p.companions))
            }
        )
    }

    fun startShift() {
        val s = _state.value
        if (!s.canStart) return
        val route = s.routes.first { it.id == s.selectedRouteId }
        store.put(LAST_BUS_KEY, s.busNumber)
        store.put(LAST_ROUTE_KEY, route.id)
        if (api == null) {
            beginShift(account.driver.name, s.busNumber, route, s.passengers)
            return
        }
        _state.update { it.copy(starting = true, startError = null, startErrorMessage = null) }
        viewModelScope.launch {
            try {
                val newSession = api.startShift(account.token, s.busNumber, route.id, s.passengers)
                session = newSession
                whatsAppPhone = newSession.whatsapp
                beginShift(newSession.driverName, newSession.busNumber, route, s.passengers)
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

    private fun beginShift(driverName: String, busNumber: String, route: TransitRoute, passengers: Passengers) {
        val shift = DriverShift(driverName, busNumber, route, clock(), passengers)
        _state.update {
            it.copy(shift = shift, starting = false, lastShiftEnd = null, rampQueue = emptyList())
        }
        send(WhatsAppReports.start(shift))
        val jobs = mutableListOf<Job>()
        val current = session
        if (api != null && current != null) {
            jobs += viewModelScope.launch { listenForRamps(api, current) }
            if (config.driverPhoneTracking) jobs += viewModelScope.launch { sendPhonePositions(api, current) }
        } else {
            // Demo: a rider asks for the ramp shortly after the shift starts.
            jobs += viewModelScope.launch {
                delay(SIM_RAMP_REQUEST_DELAY_MS)
                route.stops.firstOrNull { it.accessible }?.let { SimulatedDispatch.requestRamp(route.id, it.id, it.name) }
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

    /**
     * Ramp failure: riders are told and the bus keeps its route without ramp. Accident or truck failure:
     * riders on the route are alerted and the shift ends at once.
     */
    fun reportFailure(type: IncidentType) {
        val shift = _state.value.shift ?: return
        if (type == IncidentType.RampFailure && shift.rampFailureReported) return
        val current = session
        if (api != null && current != null) {
            val wire = when (type) {
                IncidentType.Accident -> "accident"
                IncidentType.Breakdown -> "breakdown"
                IncidentType.RampFailure -> "ramp_failure"
            }
            // Fire-and-forget on the ViewModel scope so it completes even after the shift UI closes.
            viewModelScope.launch { runCatching { api.reportIncident(current, wire) } }
        } else {
            SimulatedDispatch.reportIncident(
                Incident("driver-${shift.busNumber}", shift.busNumber.hashCode().toLong(), shift.busNumber, shift.route.id, type)
            )
        }
        send(WhatsAppReports.failure(shift, type, clock()))
        if (type == IncidentType.RampFailure) {
            _state.update { it.copy(shift = shift.copy(rampFailureReported = true)) }
        } else {
            stopShift(ShiftEnd.Emergency)
        }
    }

    fun endShift(finalLocation: FinalLocation) {
        val shift = _state.value.shift ?: return
        val current = session
        if (api != null && current != null) {
            viewModelScope.launch { runCatching { api.endShift(current, finalLocation.wire) } }
        }
        send(WhatsAppReports.end(shift, clock(), finalLocation))
        stopShift(ShiftEnd.Completed)
        // The next route starts with a fresh count.
        _state.update { it.copy(passengers = Passengers()) }
    }

    /** The screen opened [DriverUiState.whatsApp] (or could not); it stays available as [DriverUiState.lastWhatsApp]. */
    fun whatsAppOpened() = _state.update { it.copy(whatsApp = null) }

    /** Opens the last report again, for when WhatsApp was closed before sending it. */
    fun resendWhatsApp() = _state.update { s -> s.lastWhatsApp?.let { s.copy(whatsApp = it.copy(id = nextMessageId++)) } ?: s }

    private fun send(text: String) {
        val message = WhatsAppMessage(nextMessageId++, whatsAppPhone, text)
        _state.update { it.copy(whatsApp = message, lastWhatsApp = message) }
    }

    private fun stopShift(end: ShiftEnd) {
        shiftJobs.forEach { it.cancel() }
        shiftJobs = emptyList()
        session = null
        _state.update { it.copy(shift = null, rampQueue = emptyList(), lastShiftEnd = end, phoneGps = PhoneGps.Off) }
    }

    /** Demo mode: puts the bus on the map with the phone's latest fix every few seconds. */
    private suspend fun sendPhonePositions(api: RutaCreeApi, current: DriverSession) {
        _state.update { it.copy(phoneGps = PhoneGps.Searching) }
        var sentOnce = false
        while (true) {
            val fix = lastFix
            val fresh = lastFixAt?.elapsedNow()?.inWholeMilliseconds?.let { it < FIX_MAX_AGE_MS } == true
            val status = if (fix == null || !fresh) {
                if (sentOnce) PhoneGps.NoSignal else PhoneGps.Searching
            } else {
                try {
                    val speed = fix.speedKmh.takeIf { it.isFinite() }?.coerceIn(0.0, 199.0) ?: 0.0
                    api.sendPosition(current, fix.point.lat, fix.point.lon, speed)
                    sentOnce = true
                    PhoneGps.Sending
                } catch (e: ApiException) {
                    when {
                        e.unauthorized -> return stopShift(ShiftEnd.ClosedByServer)
                        e.status == 429 -> PhoneGps.Sending // one too soon: the previous one counted
                        else -> PhoneGps.NoConnection
                    }
                }
            }
            _state.update { if (it.shift != null) it.copy(phoneGps = status) else it }
            delay(POSITION_EVERY_MS)
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

    companion object {
        /** Same limit as the server. */
        const val MAX_PASSENGERS = 99
    }
}
