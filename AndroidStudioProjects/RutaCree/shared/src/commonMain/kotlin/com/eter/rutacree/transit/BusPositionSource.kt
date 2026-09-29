package com.eter.rutacree.transit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface FeedUpdate {
    data class Positions(val buses: List<BusPosition>) : FeedUpdate
    data class Connection(val online: Boolean) : FeedUpdate
    data class IncidentReported(val incident: Incident) : FeedUpdate
}

interface TransitFeed {
    val updates: Flow<FeedUpdate>
}

/**
 * Consumes Traccar's `/api/socket` stream (`devices`, `positions` and `events` messages).
 * The backend can proxy that socket unchanged; authentication is its responsibility.
 *
 * Each Traccar device must carry the attribute `ruta` (e.g. `ruta-2`). The ramp state comes from
 * the `rampa` attribute, on the position (sent by the vehicle) or on the device (set by admins).
 * Alarm events (`accident`, `fault`, …) become [Incident]s.
 */
class TraccarSocketFeed(
    private val socketUrl: String,
    private val client: HttpClient = HttpClient { install(WebSockets) },
) : TransitFeed {

    override val updates: Flow<FeedUpdate> = channelFlow {
        val devices = mutableMapOf<Long, TraccarDevice>()
        val latest = mutableMapOf<Long, TraccarPosition>()
        var backoffMs = INITIAL_BACKOFF_MS
        while (isActive) {
            try {
                client.webSocket(urlString = socketUrl) {
                    backoffMs = INITIAL_BACKOFF_MS
                    send(FeedUpdate.Connection(online = true))
                    // Every connection starts with a full snapshot, so drop what the last one knew.
                    devices.clear()
                    latest.clear()
                    var snapshot = true
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = json.decodeFromString<TraccarMessage>(frame.readText())
                        message.devices?.forEach { devices[it.id] = it }
                        message.positions?.forEach { latest[it.deviceId] = it }
                        // An empty snapshot arrives as `{}` (Go omits empty arrays) and must still clear the map.
                        if (snapshot || message.devices != null || message.positions != null) {
                            snapshot = false
                            send(FeedUpdate.Positions(latest.values.map { it.toBusPosition(devices[it.deviceId]) }))
                        }
                        message.events?.forEach { event ->
                            event.toIncident(devices[event.deviceId])?.let { send(FeedUpdate.IncidentReported(it)) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Network drop or server restart: fall through to the reconnect delay.
            }
            // The last known positions stay on screen while offline.
            send(FeedUpdate.Connection(online = false))
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private companion object {
        const val INITIAL_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val KNOTS_TO_KMH = 1.852
        val json = Json { ignoreUnknownKeys = true }
        val ACCIDENT_ALARMS = setOf("accident", "sos", "fallDown")
        val BREAKDOWN_ALARMS = setOf("fault", "powerCut", "tow", "fuelLeak")

        fun TraccarPosition.toBusPosition(device: TraccarDevice?) = BusPosition(
            deviceId = deviceId,
            name = device?.name?.takeIf { it.isNotBlank() },
            routeId = device?.routeId(),
            point = GeoPoint(latitude, longitude),
            // Traccar reports speed in knots.
            speedKmh = speed * KNOTS_TO_KMH,
            rampOperational = (attributes["rampa"] ?: device?.attributes?.get("rampa"))
                ?.jsonPrimitive?.booleanOrNull ?: false,
        )

        fun TraccarDevice.routeId() = attributes["ruta"]?.jsonPrimitive?.contentOrNull

        fun TraccarEvent.toIncident(device: TraccarDevice?): Incident? {
            if (type != "alarm") return null
            val alarm = attributes["alarm"]?.jsonPrimitive?.contentOrNull ?: return null
            val incidentType = when (alarm) {
                in ACCIDENT_ALARMS -> IncidentType.Accident
                in BREAKDOWN_ALARMS -> IncidentType.Breakdown
                else -> return null
            }
            return Incident("traccar-$id", deviceId, device?.name, device?.routeId(), incidentType)
        }
    }
}

@Serializable
internal data class TraccarMessage(
    val devices: List<TraccarDevice>? = null,
    val positions: List<TraccarPosition>? = null,
    val events: List<TraccarEvent>? = null,
)

@Serializable
internal data class TraccarDevice(
    val id: Long,
    val name: String = "",
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class TraccarPosition(
    val deviceId: Long,
    val latitude: Double,
    val longitude: Double,
    val speed: Double = 0.0,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

@Serializable
internal data class TraccarEvent(
    val id: Long = 0,
    val type: String = "",
    val deviceId: Long = 0,
    val attributes: JsonObject = JsonObject(emptyMap()),
)

data class RampRequest(val id: Long, val routeId: String, val stopId: String, val stopName: String)

/**
 * In-app stand-in for the backend while there is no server: carries ramp requests from riders
 * to drivers and incident reports from drivers to riders. The real backend replaces this.
 */
object SimulatedDispatch {
    private val _incidents = MutableSharedFlow<Incident>(extraBufferCapacity = 8)
    val incidents: SharedFlow<Incident> = _incidents.asSharedFlow()

    private val _rampRequests = MutableSharedFlow<RampRequest>(extraBufferCapacity = 8)
    val rampRequests: SharedFlow<RampRequest> = _rampRequests.asSharedFlow()

    private var nextId = 1L

    fun reportIncident(incident: Incident) {
        _incidents.tryEmit(incident)
    }

    fun requestRamp(routeId: String, stopId: String, stopName: String) {
        _rampRequests.tryEmit(RampRequest(nextId++, routeId, stopId, stopName))
    }
}

/** Fake buses driving along the loaded routes; used until a Traccar socket URL is configured. */
class SimulatedFeed(private val routes: List<TransitRoute>) : TransitFeed {

    private class SimBus(
        val id: Long,
        val route: TransitRoute,
        var alongMeters: Double,
        val speedKmh: Double,
        val ramp: Boolean,
    )

    private val positions: Flow<FeedUpdate> = flow {
        val buses = routes.flatMapIndexed { i, route ->
            val length = route.geometry.lengthMeters
            listOf(
                SimBus(245L + i * 10, route, length * 0.08, 19.0, ramp = true),
                SimBus(246L + i * 10, route, length * 0.55, 16.0, ramp = true),
                SimBus(247L + i * 10, route, length * 0.30, 22.0, ramp = false),
            )
        }
        while (true) {
            emit(
                FeedUpdate.Positions(
                    buses.map {
                        BusPosition(
                            deviceId = it.id,
                            name = null,
                            routeId = it.route.id,
                            point = it.route.geometry.pointAt(it.alongMeters),
                            speedKmh = it.speedKmh,
                            rampOperational = it.ramp,
                        )
                    }
                )
            )
            delay(TICK_MS)
            buses.forEach {
                it.alongMeters += it.speedKmh / 3.6 * (TICK_MS / 1000.0) * TIME_SCALE
                if (it.alongMeters > it.route.geometry.lengthMeters) it.alongMeters = 0.0
            }
        }
    }

    override val updates: Flow<FeedUpdate> =
        merge(positions, SimulatedDispatch.incidents.map { FeedUpdate.IncidentReported(it) })
            .onStart { emit(FeedUpdate.Connection(online = true)) }

    private companion object {
        const val TICK_MS = 2_000L
        const val TIME_SCALE = 3.0
    }
}
