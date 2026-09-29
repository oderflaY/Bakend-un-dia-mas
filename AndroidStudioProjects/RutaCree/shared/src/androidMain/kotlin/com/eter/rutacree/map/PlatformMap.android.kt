package com.eter.rutacree.map

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eter.rutacree.transit.AppConfig
import com.eter.rutacree.transit.GeoPoint
import com.eter.rutacree.ui.Neu
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

// OpenStreetMap vector tiles from OpenFreeMap: free, no key. Positron is a quiet grey base,
// so the route color and the bus stand out.
private const val LIGHT_STYLE = "https://tiles.openfreemap.org/styles/positron"
private const val DARK_STYLE = "https://tiles.openfreemap.org/styles/dark"

private const val ROUTE_ZOOM = 13.6
private const val FRAME_PADDING_PX = 140
private const val CAMERA_MS = 900

private fun GeoPoint.toPoint(): Point = Point.fromLngLat(lon, lat)
private fun GeoPoint.toLatLng() = LatLng(lat, lon)

private fun line(points: List<GeoPoint>): FeatureCollection =
    if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
    else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { it.toPoint() })))

private fun dots(points: List<GeoPoint>): FeatureCollection =
    FeatureCollection.fromFeatures(points.map { Feature.fromGeometry(it.toPoint()) })

private class MapColors(val route: Int, val stop: Int, val selected: Int, val guide: Int, val halo: Int)

/** Keeps the MapLibre view, its loaded style and the latest content in step. */
private class MapHolder(context: Context) {
    val view: MapView = MapView(context).apply { onCreate(null) }
    var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl: String? = null
    private var content: MapContent? = null
    private var colors: MapColors? = null
    private var cameraKey: Any? = null
    var onCameraMove: () -> Unit = {}

    init {
        view.getMapAsync { map ->
            this.map = map
            map.uiSettings.apply {
                isLogoEnabled = false
                isCompassEnabled = false
                isRotateGesturesEnabled = false
                isTiltGesturesEnabled = false
                // OpenStreetMap's license requires the attribution: keep the (i) button.
                isAttributionEnabled = true
            }
            map.addOnCameraMoveListener { onCameraMove() }
            map.addOnCameraIdleListener { onCameraMove() }
            styleUrl?.let { loadStyle(map, it) }
        }
    }

    fun update(url: String, content: MapContent, colors: MapColors) {
        this.content = content
        val restyle = url != styleUrl || colors.route != this.colors?.route
        this.colors = colors
        if (url != styleUrl) {
            styleUrl = url
            style = null
            map?.let { loadStyle(it, url) }
            return
        }
        val s = style ?: return
        if (restyle) addLayers(s, colors)
        render(s, content)
        moveCamera(content)
    }

    private fun loadStyle(map: MapLibreMap, url: String) {
        map.setStyle(Style.Builder().fromUri(url)) { loaded ->
            if (url != styleUrl) return@setStyle
            style = loaded
            cameraKey = null
            colors?.let { addLayers(loaded, it) }
            content?.let {
                render(loaded, it)
                moveCamera(it, animate = false)
            }
        }
    }

    private fun addLayers(s: Style, c: MapColors) {
        listOf("guide", "route", "stops", "stop-selected", "user").forEach { id ->
            s.getLayer(id)?.let(s::removeLayer)
            if (s.getSource(id) == null) s.addSource(GeoJsonSource(id))
        }
        s.addLayer(
            LineLayer("route", "route").withProperties(
                lineColor(c.route), lineWidth(7f), lineOpacity(0.95f),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            LineLayer("guide", "guide").withProperties(
                lineColor(c.guide), lineWidth(6f), lineDasharray(arrayOf(1.2f, 1.1f)),
                lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
            )
        )
        s.addLayer(
            CircleLayer("stops", "stops").withProperties(
                circleRadius(6.5f), circleColor(c.stop), circleStrokeColor(c.route), circleStrokeWidth(3f),
            )
        )
        s.addLayer(
            CircleLayer("stop-selected", "stop-selected").withProperties(
                circleRadius(10f), circleColor(c.selected), circleStrokeColor(c.route), circleStrokeWidth(3.5f),
            )
        )
        s.addLayer(
            CircleLayer("user", "user").withProperties(
                circleRadius(8f), circleColor(c.guide), circleStrokeColor(c.halo), circleStrokeWidth(3f),
            )
        )
    }

    private fun render(s: Style, content: MapContent) {
        fun set(id: String, data: FeatureCollection) = (s.getSource(id) as? GeoJsonSource)?.setGeoJson(data)
        set("route", line(content.route?.geometry?.points.orEmpty()))
        set("guide", line(content.guidance))
        set("stops", dots(content.stops.filter { it.id != content.selectedStopId }.map { it.point }))
        set("stop-selected", dots(content.stops.filter { it.id == content.selectedStopId }.map { it.point }))
        set("user", dots(listOfNotNull(content.userLocation)))
    }

    /** Moves only when what should be in view changes, so a rider can still pan the map. */
    private fun moveCamera(content: MapContent, animate: Boolean = true) {
        val map = map ?: return
        val route = content.route
        val frame = content.frame.distinct()
        val focus = content.focus
        val update = when {
            frame.size >= 2 -> {
                val bounds = LatLngBounds.Builder().includes(frame.map { it.toLatLng() }).build()
                // Rounded so small GPS moves inside the same view don't re-center it every fix.
                val key = listOf(bounds.latitudeNorth, bounds.latitudeSouth, bounds.longitudeEast, bounds.longitudeWest)
                    .map { (it * 2_000).toLong() }
                if (key == cameraKey) return
                cameraKey = key
                CameraUpdateFactory.newLatLngBounds(bounds, FRAME_PADDING_PX)
            }
            focus != null -> {
                val key = focus to content.zoom
                if (key == cameraKey) return
                cameraKey = key
                CameraUpdateFactory.newLatLngZoom(focus.toLatLng(), content.zoom ?: ROUTE_ZOOM)
            }
            route != null -> {
                if (route.id == cameraKey) return
                cameraKey = route.id
                CameraUpdateFactory.newLatLngZoom(route.geometry.pointAt(route.geometry.lengthMeters / 2).toLatLng(), ROUTE_ZOOM)
            }
            else -> return
        }
        if (animate) map.animateCamera(update, CAMERA_MS) else map.moveCamera(update)
    }
}

@Composable
actual fun PlatformMap(config: AppConfig, content: MapContent, modifier: Modifier) {
    val context = LocalContext.current
    remember { MapLibre.getInstance(context.applicationContext) }
    val holder = remember { MapHolder(context) }
    var cameraTick by remember { mutableIntStateOf(0) }
    var mapReady by remember { mutableStateOf(false) }
    holder.onCameraMove = {
        mapReady = true
        cameraTick++
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, holder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> holder.view.onStart()
                Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                Lifecycle.Event.ON_STOP -> holder.view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            holder.view.onPause()
            holder.view.onStop()
            holder.view.onDestroy()
        }
    }

    val palette = Neu.palette
    val routeColor = content.route?.let { parseHexColor(it.colorHex) } ?: palette.blue
    val colors = MapColors(
        route = routeColor.toArgb(),
        stop = palette.stopFill.toArgb(),
        selected = palette.mint.toArgb(),
        guide = palette.blueOnBg.toArgb(),
        halo = Color.White.toArgb(),
    )
    val styleUrl = if (palette.isDark) DARK_STYLE else LIGHT_STYLE

    Box(modifier) {
        AndroidView(
            factory = { holder.view },
            modifier = Modifier.fillMaxSize(),
            update = { holder.update(styleUrl, content, colors) },
        )
        // Buses are Compose views pinned to the map, like the rest of the app's markers.
        val map = holder.map
        if (map != null && mapReady) {
            val density = LocalDensity.current
            content.buses.forEach { bus ->
                key(bus.id) {
                    val half = with(density) { (if (bus.highlighted) 25.dp else 21.dp).roundToPx() }
                    // Re-projected whenever the camera moves.
                    val screen = remember(cameraTick, bus.point) { map.projection.toScreenLocation(bus.point.toLatLng()) }
                    BusMarker(bus.highlighted, Modifier.offset { IntOffset(screen.x.toInt() - half, screen.y.toInt() - half) })
                }
            }
        }
    }
}
