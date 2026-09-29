package com.eter.rutacree.transit

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** What the driver has to do at a point of the street path. */
enum class Maneuver { Straight, SlightLeft, Left, SharpLeft, SlightRight, Right, SharpRight, UTurn, Roundabout, Arrive }

data class DirectionStep(val maneuver: Maneuver, val street: String, val location: GeoPoint)

/** A driving path along real streets, from where the driver is to where they join the route. */
data class Directions(
    val path: RouteGeometry,
    val durationSeconds: Double,
    /** Maneuvers after departing, in order; the last one is the arrival. */
    val steps: List<DirectionStep>,
) {
    /** Where each step falls along [path], so progress is a single distance comparison. */
    val stepAlong: List<Double> = steps.map { path.project(it.location).alongMeters }
}

/**
 * Street routing with OSRM (OpenStreetMap data). The public server allows light use, which a few
 * drivers asking again only when they leave the suggested path fits; [baseUrl] can point to our own.
 * [walkingBaseUrl] is an OSRM server with the foot profile (FOSSGIS runs one for OpenStreetMap).
 */
class DirectionsClient(
    private val baseUrl: String = "https://router.project-osrm.org",
    private val walkingBaseUrl: String = "https://routing.openstreetmap.de/routed-foot",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 8_000
            requestTimeoutMillis = 12_000
        }
    }

    /** Null when there is no street path or the service can't be reached. */
    suspend fun driving(from: GeoPoint, to: GeoPoint): Directions? = route(baseUrl, from, to)

    /** A path on sidewalks and footways; null like [driving]. */
    suspend fun walking(from: GeoPoint, to: GeoPoint): Directions? = route(walkingBaseUrl, from, to)

    private suspend fun route(baseUrl: String, from: GeoPoint, to: GeoPoint): Directions? {
        // OSRM servers run one profile each; the name in the URL is not checked.
        val url = "$baseUrl/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
            "?overview=full&geometries=geojson&steps=true"
        val response = runCatching { client.get(url) }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null
        return runCatching { parseOsrmRoute(json, response.bodyAsText()) }.getOrNull()
    }
}

internal fun parseOsrmRoute(json: Json, body: String): Directions? {
    val root = json.parseToJsonElement(body).jsonObject
    if (root["code"]?.jsonPrimitive?.contentOrNull != "Ok") return null
    val route = root["routes"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
    val points = route.coordinates()
    if (points.size < 2) return null
    val steps = route["legs"]!!.jsonArray.flatMap { it.jsonObject["steps"]!!.jsonArray }
        .map { it.jsonObject }
        .mapNotNull { step ->
            val m = step["maneuver"]!!.jsonObject
            val type = m["type"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val loc = m["location"]!!.jsonArray
            val maneuver = maneuverOf(type, m["modifier"]?.jsonPrimitive?.contentOrNull) ?: return@mapNotNull null
            DirectionStep(
                maneuver = maneuver,
                street = step["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                location = GeoPoint(loc[1].jsonPrimitive.double, loc[0].jsonPrimitive.double),
            )
        }
    return Directions(RouteGeometry(points), route["duration"]!!.jsonPrimitive.double, steps)
}

private fun JsonObject.coordinates(): List<GeoPoint> =
    (this["geometry"]!!.jsonObject["coordinates"] as JsonArray).map {
        val c = it.jsonArray
        GeoPoint(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double)
    }

/** Null for steps that need no action (departing, or the street merely changing name straight ahead). */
private fun maneuverOf(type: String, modifier: String?): Maneuver? = when (type) {
    "depart", "new name", "notification" -> null
    "arrive" -> Maneuver.Arrive
    "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> Maneuver.Roundabout
    else -> when (modifier) {
        "uturn" -> Maneuver.UTurn
        "sharp right" -> Maneuver.SharpRight
        "right" -> Maneuver.Right
        "slight right" -> Maneuver.SlightRight
        "slight left" -> Maneuver.SlightLeft
        "left" -> Maneuver.Left
        "sharp left" -> Maneuver.SharpLeft
        else -> if (type == "continue") null else Maneuver.Straight
    }
}
