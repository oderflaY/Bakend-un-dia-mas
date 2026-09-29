package com.eter.rutacree.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.ui.Neu
import platform.UIKit.UIView

/** Plain values handed across to Swift. */
class BridgePoint(val id: String, val lat: Double, val lon: Double, val highlighted: Boolean)

/**
 * Implemented in Swift (iosApp/iosApp/MapboxBridge.swift), because the Mapbox Maps iOS SDK
 * is Swift-only and cannot be called from Kotlin/Native directly.
 */
interface MapBridge {
    fun createMapView(
        accessToken: String,
        styleUrl: String,
        dark: Boolean,
        centerLat: Double,
        centerLon: Double,
        zoom: Double,
    ): UIView
    fun updateRoute(view: UIView, points: List<BridgePoint>, colorHex: String)
    fun updateStops(view: UIView, stops: List<BridgePoint>, colorHex: String)
    fun updateBuses(view: UIView, buses: List<BridgePoint>)
    fun updateUser(view: UIView, user: BridgePoint?)
    fun focus(view: UIView, lat: Double, lon: Double)
}

val LocalMapBridge = staticCompositionLocalOf<MapBridge?> { null }

private const val ROUTE_ZOOM = 13.6

@Composable
actual fun PlatformMap(config: AppConfig, content: MapContent, modifier: Modifier) {
    val bridge = LocalMapBridge.current
    val route = content.route
    if (bridge == null || config.mapboxAccessToken.isBlank() || route == null) {
        SchematicMap(content, modifier)
        return
    }
    val center = route.geometry.pointAt(route.geometry.lengthMeters / 2)
    val routePoints = route.geometry.points.mapIndexed { i, p -> BridgePoint("r$i", p.lat, p.lon, false) }
    val stops = content.stops.map { BridgePoint(it.id, it.point.lat, it.point.lon, it.id == content.selectedStopId) }
    val buses = content.buses.map { BridgePoint(it.id.toString(), it.point.lat, it.point.lon, it.highlighted) }
    val user = content.userLocation?.let { BridgePoint("user", it.lat, it.lon, false) }
    val focus = content.focus

    val dark = Neu.palette.isDark
    val styleUrl = config.styleUrl(dark)
    // Recreate the native view when the theme flips, so style and pin artwork match.
    key(styleUrl) {
        UIKitView(
            factory = { bridge.createMapView(config.mapboxAccessToken, styleUrl, dark, center.lat, center.lon, ROUTE_ZOOM) },
            modifier = modifier,
            update = { view ->
                bridge.updateRoute(view, routePoints, route.colorHex)
                bridge.updateStops(view, stops, route.colorHex)
                bridge.updateBuses(view, buses)
                bridge.updateUser(view, user)
                focus?.let { bridge.focus(view, it.lat, it.lon) }
            },
        )
    }
}
