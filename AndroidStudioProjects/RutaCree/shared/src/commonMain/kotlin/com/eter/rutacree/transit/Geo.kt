package com.eter.rutacree.transit

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_000.0

private fun Double.rad() = this * PI / 180.0

fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val dLat = (b.lat - a.lat).rad()
    val dLon = (b.lon - a.lon).rad()
    val h = sin(dLat / 2).let { it * it } +
        cos(a.lat.rad()) * cos(b.lat.rad()) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

/** Initial compass bearing from [a] to [b], in degrees clockwise from north (0 until 360). */
fun bearingDegrees(a: GeoPoint, b: GeoPoint): Double {
    val dLon = (b.lon - a.lon).rad()
    val y = sin(dLon) * cos(b.lat.rad())
    val x = cos(a.lat.rad()) * sin(b.lat.rad()) - sin(a.lat.rad()) * cos(b.lat.rad()) * cos(dLon)
    return (atan2(y, x) * 180.0 / PI + 360.0) % 360.0
}

enum class Compass { North, NorthEast, East, SouthEast, South, SouthWest, West, NorthWest }

fun compassOf(bearingDegrees: Double): Compass =
    Compass.entries[(((bearingDegrees % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

/** Where a point falls on a route: distance travelled from the start, and how far it is from the line. */
data class RouteProjection(val alongMeters: Double, val offsetMeters: Double, val snapped: GeoPoint)

/**
 * A route polyline with precomputed cumulative distances, so positions can be
 * expressed as "meters from the terminal" and ETAs follow the real path instead of a straight line.
 */
class RouteGeometry(val points: List<GeoPoint>) {
    private val cumulative: DoubleArray = DoubleArray(points.size).also { acc ->
        for (i in 1 until points.size) acc[i] = acc[i - 1] + distanceMeters(points[i - 1], points[i])
    }

    val lengthMeters: Double get() = cumulative.lastOrNull() ?: 0.0

    fun project(p: GeoPoint): RouteProjection {
        if (points.size < 2) return RouteProjection(0.0, points.firstOrNull()?.let { distanceMeters(it, p) } ?: 0.0, p)
        // Local equirectangular projection is accurate to centimeters at city scale.
        val kx = cos(p.lat.rad()) * EARTH_RADIUS_M * PI / 180.0
        val ky = EARTH_RADIUS_M * PI / 180.0
        var best = RouteProjection(0.0, Double.MAX_VALUE, points.first())
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val ax = 0.0
            val ay = 0.0
            val bx = (b.lon - a.lon) * kx
            val by = (b.lat - a.lat) * ky
            val px = (p.lon - a.lon) * kx
            val py = (p.lat - a.lat) * ky
            val len2 = bx * bx + by * by
            val t = if (len2 == 0.0) 0.0 else ((px - ax) * bx + (py - ay) * by) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val sx = bx * tc
            val sy = by * tc
            val offset = sqrt((px - sx) * (px - sx) + (py - sy) * (py - sy))
            if (offset < best.offsetMeters) {
                val segLen = cumulative[i + 1] - cumulative[i]
                best = RouteProjection(
                    alongMeters = cumulative[i] + segLen * tc,
                    offsetMeters = offset,
                    snapped = GeoPoint(a.lat + (b.lat - a.lat) * tc, a.lon + (b.lon - a.lon) * tc),
                )
            }
        }
        return best
    }

    fun pointAt(alongMeters: Double): GeoPoint {
        if (points.isEmpty()) return GeoPoint(0.0, 0.0)
        val d = alongMeters.coerceIn(0.0, lengthMeters)
        var i = 1
        while (i < cumulative.size - 1 && cumulative[i] < d) i++
        if (i >= points.size) return points.last()
        val segLen = cumulative[i] - cumulative[i - 1]
        val t = if (segLen == 0.0) 0.0 else (d - cumulative[i - 1]) / segLen
        val a = points[i - 1]
        val b = points[i]
        return GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** The polyline from [alongMeters] to the end. */
    fun pointsAfter(alongMeters: Double): List<GeoPoint> {
        if (points.isEmpty()) return emptyList()
        val from = alongMeters.coerceIn(0.0, lengthMeters)
        return listOf(pointAt(from)) + points.filterIndexed { i, _ -> cumulative[i] > from }
    }
}
