package com.eter.rutacree.api

import com.eter.rutacree.transit.GeoPoint
import com.eter.rutacree.transit.RampRequest
import com.eter.rutacree.transit.TransitRoute
import com.eter.rutacree.transit.parseRouteGeoJson
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/** Failure talking to the backend, with the HTTP status when there was a response. */
class ApiException(val status: Int?, message: String, val code: String? = null) : Exception(message) {
    val unauthorized: Boolean get() = status == HttpStatusCode.Unauthorized.value
    val locked: Boolean get() = status == HttpStatusCode.TooManyRequests.value
    val conflict: Boolean get() = status == HttpStatusCode.Conflict.value
    val badRequest: Boolean get() = status == HttpStatusCode.BadRequest.value
}

@Serializable
data class DriverSession(
    val token: String,
    val shiftId: Long,
    val busId: Long,
    val busNumber: String,
    val routeId: String,
    val driverName: String,
    val rampOperational: Boolean,
    /** "traccar" when the bus position comes from Traccar Client on the driver's phone. */
    val positionSource: String = "app",
    val trackerDeviceId: String? = null,
    val trackerUrl: String? = null,
) {
    val usesTraccar: Boolean get() = positionSource == "traccar"
}

@Serializable
data class TrackerStatus(
    val positionSource: String = "app",
    val trackerDeviceId: String? = null,
    val trackerUrl: String? = null,
    val trackerConnected: Boolean = false,
    /** Age of Traccar Client's last fix. */
    val lastFixAgeSeconds: Int? = null,
    /** Age of the bus's last position from any source (Traccar Client or this app's GPS). */
    val busFixAgeSeconds: Int? = null,
)

@Serializable
data class RiderFavorites(val home: String = "", val center: String = "", val therapy: String = "")

@Serializable
data class RiderProfile(
    val id: Long = 0,
    val email: String = "",
    val name: String = "",
    val userType: String = "rider",
    val wheelchair: Boolean = false,
    val lowVision: Boolean = false,
    val reducedMobility: Boolean = false,
    val favorites: RiderFavorites = RiderFavorites(),
)

@Serializable
data class RiderSession(val token: String, val rider: RiderProfile)

@Serializable
data class DriverInfo(val id: String, val name: String, val email: String = "")

/** A signed-in driver without a shift yet: the token only allows starting a shift. */
@Serializable
data class DriverAccount(val token: String, val driver: DriverInfo)

/** The single sign-in answers with the role the account has. */
sealed interface LoginResult {
    data class Rider(val session: RiderSession) : LoginResult
    data class Driver(val account: DriverAccount) : LoginResult
}

enum class RampStatus { Pending, Acknowledged, Expired }

@Serializable
data class RampTicket(val id: Long, val status: String, val driversNotified: Int = 0)

enum class ReportKind(val wire: String) {
    RampBroken("ramp_broken"),
    DidNotStop("did_not_stop"),
    BusFull("bus_full"),
    StopBlocked("stop_blocked"),
}

sealed interface DriverSocketMessage {
    data class Requests(val requests: List<RampRequest>) : DriverSocketMessage
    data class Acknowledged(val ids: List<Long>) : DriverSocketMessage
    data class Connection(val online: Boolean) : DriverSocketMessage
}

/**
 * Client for the RutaCree backend (`bakend ruta cree`). [baseUrl] is e.g. `http://192.168.1.10:8080`
 * in development or `https://api.rutacree.mx` in production; WebSocket URLs are derived from it.
 * Tokens always travel in the Authorization header, never in the URL.
 */
class RutaCreeApi(baseUrl: String) {
    private val base = baseUrl.trimEnd('/')
    private val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")

    val publicSocketUrl: String = "$wsBase/api/socket"

    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient {
        install(WebSockets)
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
    }

    // ---- public ----

    suspend fun routes(): List<TransitRoute> {
        val body = call { client.get("$base/api/v1/routes") }
        return json.parseToJsonElement(body).let { it as JsonObject }["routes"]!!.jsonArray
            .map { parseRouteGeoJson(it.toString()) }
    }

    suspend fun requestRamp(routeId: String, stopId: String): RampTicket =
        json.decodeFromString(call { client.post("$base/api/v1/ramp-requests") { jsonBody(RampBody(routeId, stopId)) } })

    suspend fun rampStatus(id: Long): RampStatus {
        val body = json.decodeFromString<StatusBody>(call { client.get("$base/api/v1/ramp-requests/$id") })
        return when (body.status) {
            "acknowledged" -> RampStatus.Acknowledged
            "pending" -> RampStatus.Pending
            else -> RampStatus.Expired
        }
    }

    suspend fun sendReport(kind: ReportKind, routeId: String?, busId: Long?, comment: String) {
        call {
            client.post("$base/api/v1/reports") {
                jsonBody(ReportBody(kind.wire, routeId, busId, comment.trim().take(500)))
            }
        }
    }

    // ---- rider accounts ----

    suspend fun register(email: String, password: String, name: String): RiderSession =
        json.decodeFromString(call { client.post("$base/api/v1/riders") { jsonBody(RegisterBody(email, password, name)) } })

    /** One sign-in for drivers and riders; the server decides which screens the person gets. */
    suspend fun login(email: String, password: String): LoginResult {
        val body = call { client.post("$base/api/v1/auth/login") { jsonBody(LoginBody(email, password)) } }
        val role = (json.parseToJsonElement(body) as JsonObject)["role"]?.jsonPrimitive?.contentOrNull
        return if (role == "driver") {
            LoginResult.Driver(json.decodeFromString<DriverAccount>(body))
        } else {
            LoginResult.Rider(json.decodeFromString<RiderSession>(body))
        }
    }

    suspend fun me(token: String): RiderProfile =
        json.decodeFromString(call { client.get("$base/api/v1/riders/me") { bearerAuth(token) } })

    suspend fun updateProfile(token: String, profile: RiderProfile): RiderProfile =
        json.decodeFromString(
            call {
                client.put("$base/api/v1/riders/me") {
                    bearerAuth(token)
                    jsonBody(
                        ProfileBody(
                            profile.name, profile.userType, profile.wheelchair, profile.lowVision,
                            profile.reducedMobility, profile.favorites,
                        )
                    )
                }
            }
        )

    suspend fun deleteAccount(token: String) {
        call { client.delete("$base/api/v1/riders/me") { bearerAuth(token) } }
    }

    // ---- driver ----

    suspend fun trackerStatus(session: DriverSession): TrackerStatus =
        json.decodeFromString(call { client.get("$base/api/v1/driver/tracker") { bearerAuth(session.token) } })


    suspend fun driverMe(accountToken: String): DriverInfo =
        json.decodeFromString(call { client.get("$base/api/v1/driver/me") { bearerAuth(accountToken) } })

    suspend fun startShift(accountToken: String, busNumber: String, routeId: String): DriverSession =
        json.decodeFromString(
            call {
                client.post("$base/api/v1/driver/shifts") {
                    bearerAuth(accountToken)
                    jsonBody(ShiftBody(busNumber, routeId))
                }
            }
        )

    suspend fun reportPosition(session: DriverSession, point: GeoPoint, speedKmh: Double) {
        call {
            client.post("$base/api/v1/driver/positions") {
                bearerAuth(session.token)
                jsonBody(PositionBody(point.lat, point.lon, speedKmh.coerceIn(0.0, 200.0)))
            }
        }
    }

    suspend fun reportIncident(session: DriverSession, accident: Boolean) {
        call {
            client.post("$base/api/v1/driver/incidents") {
                bearerAuth(session.token)
                jsonBody(IncidentBody(if (accident) "accident" else "breakdown"))
            }
        }
    }

    suspend fun endShift(session: DriverSession, finalLocation: String) {
        call {
            client.post("$base/api/v1/driver/shifts/end") {
                bearerAuth(session.token)
                jsonBody(EndShiftBody(finalLocation))
            }
        }
    }

    /** 409 means another driver already took it, which is fine for this driver too. */
    suspend fun acknowledgeRamp(session: DriverSession, id: Long) {
        val response = client.post("$base/api/v1/driver/ramp-requests/$id/ack") { bearerAuth(session.token) }
        if (!response.status.isSuccess() && response.status != HttpStatusCode.Conflict) throw response.toException()
    }

    /** Ramp requests for the driver's route; pending ones are replayed by the server on every (re)connect. */
    fun driverSocket(session: DriverSession): Flow<DriverSocketMessage> = channelFlow {
        var backoffMs = 2_000L
        while (isActive) {
            try {
                client.webSocket(urlString = "$wsBase/api/v1/driver/socket", request = { bearerAuth(session.token) }) {
                    backoffMs = 2_000L
                    send(DriverSocketMessage.Connection(online = true))
                    for (frame in incoming) {
                        if (frame !is Frame.Text) continue
                        val message = json.decodeFromString<DriverMessage>(frame.readText())
                        message.rampRequests?.takeIf { it.isNotEmpty() }?.let { list ->
                            send(DriverSocketMessage.Requests(list.map { RampRequest(it.id, it.routeId, it.stopId, it.stopName) }))
                        }
                        message.rampAcks?.takeIf { it.isNotEmpty() }?.let { send(DriverSocketMessage.Acknowledged(it)) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Dropped connection: retry below.
            }
            send(DriverSocketMessage.Connection(online = false))
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    private suspend fun call(block: suspend () -> HttpResponse): String {
        val response = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(null, e.message ?: "network error")
        }
        if (!response.status.isSuccess()) throw response.toException()
        return response.bodyAsText()
    }

    private suspend fun HttpResponse.toException(): ApiException {
        val body = runCatching { json.decodeFromString<ErrorBody>(bodyAsText()) }.getOrNull()
        return ApiException(status.value, body?.message?.ifBlank { null } ?: status.description, body?.error)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(value))
    }

    @Serializable private data class RegisterBody(val email: String, val password: String, val name: String)
    @Serializable private data class LoginBody(val email: String, val password: String)
    @Serializable
    private data class ProfileBody(
        val name: String,
        val userType: String,
        val wheelchair: Boolean,
        val lowVision: Boolean,
        val reducedMobility: Boolean,
        val favorites: RiderFavorites,
    )
    @Serializable private data class RampBody(val routeId: String, val stopId: String)
    @Serializable private data class StatusBody(val status: String)
    @Serializable private data class ReportBody(val type: String, val routeId: String?, val busId: Long?, val comment: String)
    @Serializable private data class ShiftBody(val busNumber: String, val routeId: String)
    @Serializable private data class PositionBody(val lat: Double, val lon: Double, val speedKmh: Double)
    @Serializable private data class IncidentBody(val type: String)
    @Serializable private data class EndShiftBody(val finalLocation: String)
    @Serializable private data class ErrorBody(val error: String = "", val message: String = "")

    @Serializable
    private data class WireRampRequest(val id: Long, val routeId: String, val stopId: String, val stopName: String)

    @Serializable
    private data class DriverMessage(val rampRequests: List<WireRampRequest>? = null, val rampAcks: List<Long>? = null)
}
