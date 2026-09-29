package com.eter.rutacree.map

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.BusOnRoute
import com.eter.rutacree.transit.GeoPoint
import com.eter.rutacree.transit.Stop
import com.eter.rutacree.transit.TransitRoute
import com.eter.rutacree.ui.AppIcons
import com.eter.rutacree.ui.Neu
import com.eter.rutacree.ui.neuCard
import kotlin.math.abs

data class MapBus(val id: Long, val point: GeoPoint, val highlighted: Boolean)

/** Everything a map renderer needs; platform maps only draw it. */
data class MapContent(
    val route: TransitRoute?,
    val stops: List<Stop>,
    val selectedStopId: String?,
    val buses: List<MapBus>,
    /** The rider's GPS position, drawn as a blue dot. */
    val userLocation: GeoPoint? = null,
    /** Where the camera should center (the selected stop). */
    val focus: GeoPoint? = null,
    /** Camera zoom around [focus]; null keeps the route overview zoom. */
    val zoom: Double? = null,
    /** Street path the driver should follow to reach the route, drawn dashed. */
    val guidance: List<GeoPoint> = emptyList(),
    /** Points the camera keeps in view; when set it takes precedence over [focus]. */
    val frame: List<GeoPoint> = emptyList(),
)

/** Native street map per platform. Must fall back to [SchematicMap] when it cannot render. */
@Composable
expect fun PlatformMap(config: AppConfig, content: MapContent, modifier: Modifier = Modifier)

fun parseHexColor(hex: String): Color =
    Color(0xFF000000 or hex.removePrefix("#").toLong(16))

private const val JUMP_THRESHOLD_M = 800f

/**
 * Glides each bus along the route between GPS fixes instead of jumping.
 * Interpolating the distance along the route keeps pins on the street, even around corners.
 */
@Composable
fun rememberAnimatedBuses(route: TransitRoute?, buses: List<BusOnRoute>, highlightedId: Long?): List<MapBus> {
    if (route == null) return emptyList()
    return buses.map { bus ->
        key(bus.position.deviceId) {
            val along = remember { Animatable(bus.alongMeters.toFloat()) }
            LaunchedEffect(bus.alongMeters) {
                val target = bus.alongMeters.toFloat()
                if (abs(target - along.value) > JUMP_THRESHOLD_M) along.snapTo(target)
                else along.animateTo(target, tween(durationMillis = 1_900, easing = LinearEasing))
            }
            MapBus(
                id = bus.position.deviceId,
                point = route.geometry.pointAt(along.value.toDouble()),
                highlighted = bus.position.deviceId == highlightedId,
            )
        }
    }
}

@Composable
fun BusMarker(highlighted: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(if (highlighted) 50.dp else 42.dp)
            .neuCard(CircleShape, if (highlighted) 5.dp else 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(AppIcons.Bus, null, tint = Neu.Blue, modifier = Modifier.size(if (highlighted) 28.dp else 22.dp))
    }
}

/** Offline rendering of the route, used when a platform has no street map available. */
@Composable
fun SchematicMap(content: MapContent, modifier: Modifier = Modifier) {
    val route = content.route
    val palette = Neu.palette
    BoxWithConstraints(modifier.background(palette.bg)) {
        if (route == null) return@BoxWithConstraints
        // The bus may be away from the route: keep it and its path to the route in view too.
        val pts = route.geometry.points + content.guidance + listOfNotNull(content.userLocation) +
            content.buses.map { it.point }
        val minLat = pts.minOf { it.lat }
        val maxLat = pts.maxOf { it.lat }
        val minLon = pts.minOf { it.lon }
        val maxLon = pts.maxOf { it.lon }
        val span = maxOf(maxLat - minLat, maxLon - minLon, 1e-4)
        val padFraction = 0.14f

        fun fx(p: GeoPoint) = (padFraction + (1 - 2 * padFraction) * ((p.lon - minLon) / span).toFloat())
        fun fy(p: GeoPoint) = (padFraction + (1 - 2 * padFraction) * ((maxLat - p.lat) / span).toFloat())

        val routeColor = parseHexColor(route.colorHex)
        Canvas(Modifier.matchParentSize()) {
            val grid = palette.mapGrid
            for (i in 1..5) {
                drawLine(grid, Offset(size.width * i / 6f, 0f), Offset(size.width * i / 6f, size.height), 16f)
                drawLine(grid, Offset(0f, size.height * i / 6f), Offset(size.width, size.height * i / 6f), 16f)
            }
            val path = Path().apply {
                route.geometry.points.forEachIndexed { i, p ->
                    val o = Offset(fx(p) * size.width, fy(p) * size.height)
                    if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                }
            }
            drawPath(path, routeColor, style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            if (content.guidance.size >= 2) {
                val guide = Path().apply {
                    content.guidance.forEachIndexed { i, p ->
                        val o = Offset(fx(p) * size.width, fy(p) * size.height)
                        if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y)
                    }
                }
                drawPath(
                    guide,
                    palette.blueOnBg,
                    style = Stroke(width = 9f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 16f))),
                )
            }
            content.stops.forEach { stop ->
                val c = Offset(fx(stop.point) * size.width, fy(stop.point) * size.height)
                val selected = stop.id == content.selectedStopId
                if (selected) drawCircle(palette.mint.copy(alpha = 0.45f), radius = 30f, center = c)
                drawCircle(if (selected) palette.mint else palette.stopFill, radius = if (selected) 15f else 10f, center = c)
                drawCircle(routeColor, radius = if (selected) 15f else 10f, center = c, style = Stroke(4f))
            }
            content.userLocation?.let { user ->
                val c = Offset(fx(user) * size.width, fy(user) * size.height)
                drawCircle(palette.blue.copy(alpha = 0.3f), radius = 26f, center = c)
                drawCircle(palette.stopFill, radius = 12f, center = c)
                drawCircle(palette.blueOnBg, radius = 8f, center = c)
            }
        }
        content.buses.forEach { bus ->
            val half = if (bus.highlighted) 25.dp else 21.dp
            BusMarker(
                bus.highlighted,
                Modifier.offset(x = maxWidth * fx(bus.point) - half, y = maxHeight * fy(bus.point) - half),
            )
        }
    }
}
