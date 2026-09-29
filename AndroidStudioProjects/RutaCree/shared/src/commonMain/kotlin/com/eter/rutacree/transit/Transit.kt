package com.eter.rutacree.transit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.roundToInt

data class Stop(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val accessible: Boolean,
    val alongMeters: Double,
    /** Official departures as minutes after midnight, ascending. */
    val schedule: List<Int> = emptyList(),
)

/** Next official departures from [nowMinuteOfDay]; wraps to the first ones of the next day. */
fun Stop.nextDepartures(nowMinuteOfDay: Int, count: Int = 4): List<Int> {
    val upcoming = schedule.filter { it >= nowMinuteOfDay }
    return (upcoming + schedule).take(count)
}

/** Minutes from [nowMinuteOfDay] until [departure], counting into the next day when it already passed. */
fun minutesUntil(departure: Int, nowMinuteOfDay: Int): Int = ((departure - nowMinuteOfDay) % 1440 + 1440) % 1440

fun formatClock(minuteOfDay: Int): String {
    val m = ((minuteOfDay % 1440) + 1440) % 1440
    return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
}

private fun parseClock(text: String): Int? {
    val parts = text.split(":")
    val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
    val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
    return h * 60 + m
}

enum class IncidentType { Accident, Breakdown }

/** A bus that can no longer serve its route (from a Traccar alarm or a driver's panic report). */
data class Incident(
    val id: String,
    val deviceId: Long,
    val busName: String?,
    val routeId: String?,
    val type: IncidentType,
)

class TransitRoute(
    val id: String,
    val name: String,
    val colorHex: String,
    val geometry: RouteGeometry,
    val stops: List<Stop>,
)

/** A live vehicle as delivered by Traccar (or the simulator), already tied to a route. */
data class BusPosition(
    val deviceId: Long,
    /** Label from Traccar; null when the server has none (the UI then shows a localized fallback). */
    val name: String?,
    val routeId: String?,
    val point: GeoPoint,
    val speedKmh: Double,
    val rampOperational: Boolean,
)

/** A bus placed on its route. */
data class BusOnRoute(
    val position: BusPosition,
    val alongMeters: Double,
    val averageSpeedKmh: Double,
)

data class Arrival(val bus: BusOnRoute, val distanceMeters: Double, val etaSeconds: Int) {
    val minutes: Int get() = max(1, (etaSeconds / 60.0).roundToInt())
}

private const val OFF_ROUTE_TOLERANCE_M = 120.0
private const val STOP_DWELL_SECONDS = 20
private const val MIN_SPEED_KMH = 12.0
private const val MAX_SPEED_KMH = 40.0

fun TransitRoute.place(bus: BusPosition, averageSpeedKmh: Double): BusOnRoute? {
    val projection = geometry.project(bus.point)
    if (projection.offsetMeters > OFF_ROUTE_TOLERANCE_M) return null
    return BusOnRoute(bus, projection.alongMeters, averageSpeedKmh)
}

/**
 * Next bus with a working ramp that has not yet passed [stop].
 * Distance is measured along the route, and each intermediate stop adds dwell time,
 * because a bus never drives the straight line a car-routing matrix would assume.
 */
fun TransitRoute.nextArrival(stop: Stop, buses: List<BusOnRoute>): Arrival? =
    buses
        .filter { it.position.rampOperational && it.alongMeters <= stop.alongMeters + 25 }
        .map { bus ->
            val distance = max(0.0, stop.alongMeters - bus.alongMeters)
            val speedMs = bus.averageSpeedKmh.coerceIn(MIN_SPEED_KMH, MAX_SPEED_KMH) / 3.6
            val stopsBetween = stops.count { it.alongMeters > bus.alongMeters && it.alongMeters < stop.alongMeters }
            Arrival(bus, distance, (distance / speedMs).roundToInt() + stopsBetween * STOP_DWELL_SECONDS)
        }
        .minByOrNull { it.etaSeconds }

const val GEOFENCE_RADIUS_M = 150.0

/** The stop closest to the rider; [inside] when within the stop's geofence. */
data class Geofence(val stop: Stop, val distanceMeters: Double) {
    val inside: Boolean get() = distanceMeters <= GEOFENCE_RADIUS_M
}

fun nearestStop(location: GeoPoint, stops: List<Stop>): Geofence? =
    stops.map { Geofence(it, distanceMeters(location, it.point)) }.minByOrNull { it.distanceMeters }

/**
 * Parses a GeoJSON FeatureCollection with one LineString (the route, with `id`, `name`, `color`)
 * and Point features for the stops (with `name` and `accessible`).
 */
fun parseRouteGeoJson(text: String): TransitRoute {
    val features = Json.parseToJsonElement(text).jsonObject["features"]!!.jsonArray.map { it.jsonObject }
    val line = features.first { it.geometryType() == "LineString" }
    val props = line["properties"]!!.jsonObject
    val geometry = RouteGeometry(line.coordinates().map { it.toGeoPoint() })

    val stops = features.filter { it.geometryType() == "Point" }.mapIndexed { i, f ->
        val p = f["properties"]!!.jsonObject
        val point = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.toGeoPoint()
        Stop(
            id = p["id"]?.jsonPrimitive?.content ?: "stop-$i",
            name = p["name"]!!.jsonPrimitive.content,
            point = point,
            accessible = p["accessible"]?.jsonPrimitive?.booleanOrNull ?: false,
            alongMeters = geometry.project(point).alongMeters,
            schedule = p["horarios"]?.jsonArray?.mapNotNull { parseClock(it.jsonPrimitive.content) }?.sorted().orEmpty(),
        )
    }.sortedBy { it.alongMeters }

    return TransitRoute(
        id = props["id"]!!.jsonPrimitive.content,
        name = props["name"]!!.jsonPrimitive.content,
        colorHex = props["color"]?.jsonPrimitive?.content ?: "#8ECAE6",
        geometry = geometry,
        stops = stops,
    )
}

private fun JsonObject.geometryType() = this["geometry"]?.jsonObject?.get("type")?.jsonPrimitive?.content

private fun JsonObject.coordinates(): List<JsonArray> =
    this["geometry"]!!.jsonObject["coordinates"]!!.jsonArray.map { it.jsonArray }

// GeoJSON order is [longitude, latitude].
private fun JsonArray.toGeoPoint() = GeoPoint(lat = this[1].jsonPrimitive.double, lon = this[0].jsonPrimitive.double)
