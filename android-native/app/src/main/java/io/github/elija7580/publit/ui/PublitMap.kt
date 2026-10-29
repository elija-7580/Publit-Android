package io.github.elija7580.publit.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.elija7580.publit.core.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.toColor
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory as P
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.max

/** What a map shows (GoogleMapContent on iOS): lines, then round dots, drawn in this order. */
data class MapContent(val lines: List<Line> = emptyList(), val dots: List<Dot> = emptyList()) {
    data class Line(val points: List<LatLon>, val color: Color, val width: Float)
    /** A round dot: stop, vehicle, start, destination. */
    data class Dot(val at: LatLon, val fill: Color, val border: Color, val size: Float, val borderWidth: Float)
}

/** Where a map looks (GoogleCamera on iOS). */
sealed class MapCamera {
    data class Fit(val points: List<LatLon>) : MapCamera()
    data class Around(val center: LatLon, val meters: Double) : MapCamera()
}

/**
 * Publit's route and trip maps on OpenStreetMap (MapLibre, OpenFreeMap tiles). The "Transit" look
 * (pale streets) is the default, as MapBase.muted on iOS. Runs only while the screen is resumed.
 */
@Composable
fun PublitMap(content: MapContent, camera: MapCamera, modifier: Modifier = Modifier, description: String? = null) {
    val context = LocalContext.current
    val dark = LocalInk.current.dark
    val holder = remember { MapHolder(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> holder.lifecycle(e) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); holder.destroy() }
    }
    AndroidView({ holder.view }, modifier.then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)) {
        holder.update(content, camera, dark)
    }
}

private class MapHolder(ctx: Context) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleUrl = ""
    private var content = MapContent()
    private var camera: MapCamera? = null
    private var shownCamera: MapCamera? = null
    private var dark = false
    private var destroyed = false
    private val density = ctx.resources.displayMetrics.density

    init {
        MapLibre.getInstance(ctx)
        view = MapView(ctx, MapLibreMapOptions.createFromAttributes(ctx).textureMode(true))
        view.onCreate(null)
        view.getMapAsync { m ->
            if (destroyed) return@getMapAsync
            map = m
            runCatching {
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.isAttributionEnabled = true
                m.uiSettings.isTiltGesturesEnabled = false
                m.uiSettings.isCompassEnabled = true
            }
            refresh()
        }
    }

    fun lifecycle(e: Lifecycle.Event) {
        if (destroyed) return
        runCatching {
            when (e) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> {}
            }
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        runCatching { view.onPause(); view.onStop(); view.onDestroy() }
        map = null; style = null
    }

    fun update(content: MapContent, camera: MapCamera, dark: Boolean) {
        val changed = content != this.content || dark != this.dark
        this.content = content; this.camera = camera; this.dark = dark
        if (changed || camera != shownCamera) refresh()
    }

    private fun refresh() {
        val m = map ?: return
        val url = if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
        if (url != styleUrl) {
            styleUrl = url
            style = null
            m.setStyle(Style.Builder().fromUri(url)) { s ->
                if (destroyed || styleUrl != url) return@setStyle
                runCatching { setup(s) }
                style = s
                refresh()
            }
            return
        }
        val s = style ?: return
        runCatching { draw(s) }
        val cam = camera
        if (cam != null && cam != shownCamera) {
            val animated = shownCamera != null
            shownCamera = cam
            move(m, cam, animated)
        }
    }

    private fun setup(s: Style) {
        s.addSource(GeoJsonSource("pub-lines"))
        s.addSource(GeoJsonSource("pub-dots"))
        s.addLayer(LineLayer("pub-lines", "pub-lines").withProperties(
            P.lineColor(toColor(get("color"))), P.lineWidth(get("width")),
            P.lineCap(Property.LINE_CAP_ROUND), P.lineJoin(Property.LINE_JOIN_ROUND)))
        s.addLayer(CircleLayer("pub-dots", "pub-dots").withProperties(
            P.circleColor(toColor(get("fill"))), P.circleRadius(get("radius")),
            P.circleStrokeColor(toColor(get("border"))), P.circleStrokeWidth(get("stroke"))))
    }

    private fun rgba(c: Color): String {
        val argb = c.toArgb()
        return "rgba(${(argb shr 16) and 255},${(argb shr 8) and 255},${argb and 255},${((argb ushr 24) / 255.0)})"
    }

    private fun draw(s: Style) {
        val lines = content.lines.filter { it.points.size > 1 }.map { l ->
            Feature.fromGeometry(LineString.fromLngLats(l.points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
                addStringProperty("color", rgba(l.color)); addNumberProperty("width", l.width)
            }
        }
        val dots = content.dots.map { d ->
            Feature.fromGeometry(Point.fromLngLat(d.at.lon, d.at.lat)).apply {
                addStringProperty("fill", rgba(d.fill)); addStringProperty("border", rgba(d.border))
                addNumberProperty("radius", d.size / 2); addNumberProperty("stroke", d.borderWidth)
            }
        }
        s.getSourceAs<GeoJsonSource>("pub-lines")?.setGeoJson(FeatureCollection.fromFeatures(lines))
        s.getSourceAs<GeoJsonSource>("pub-dots")?.setGeoJson(FeatureCollection.fromFeatures(dots))
    }

    private fun move(m: MapLibreMap, cam: MapCamera, animated: Boolean) {
        if (view.width == 0 || view.height == 0) {
            view.post { if (!destroyed) move(m, cam, animated) }
            return
        }
        val update = runCatching {
            when (cam) {
                is MapCamera.Fit -> {
                    val pts = cam.points
                    when {
                        pts.isEmpty() -> null
                        pts.size == 1 -> CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 15.0)
                        else -> {
                            val b = LatLngBounds.Builder()
                            pts.forEach { b.include(LatLng(it.lat, it.lon)) }
                            val pad = (40 * density).toInt()
                            CameraUpdateFactory.newLatLngBounds(b.build(), pad)
                        }
                    }
                }
                is MapCamera.Around -> {
                    val dLat = cam.meters / 2 / 111_320
                    val dLon = dLat / max(cos(Math.toRadians(cam.center.lat)), 0.01)
                    val b = LatLngBounds.Builder()
                        .include(LatLng(cam.center.lat - dLat, cam.center.lon - dLon))
                        .include(LatLng(cam.center.lat + dLat, cam.center.lon + dLon)).build()
                    CameraUpdateFactory.newLatLngBounds(b, 0)
                }
            }
        }.getOrNull() ?: return
        runCatching { if (animated) m.animateCamera(update, 600) else m.moveCamera(update) }
    }
}

/** Line colour as drawn on maps (LineColors.strokeHex). */
fun lineStroke(style: io.github.elija7580.publit.data.RouteStyled, dark: Boolean): Color =
    hexColor(io.github.elija7580.publit.data.LineColors.strokeHex(style.lineColors.background, dark))

/** A route on the map (RouteMap on iOS): lines per leg, change points, start and destination. */
@Composable
fun RouteMap(it: io.github.elija7580.publit.data.Itinerary, modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    val ahead = if (ink.dark) Color(0xFF409CFF) else Color(0xFF0A7CFF)
    val content = remember(it.routeKey, ink.dark) {
        val dots = ArrayList<MapContent.Dot>()
        it.legs.filter { l -> l.isTransit }.drop(1).forEach { l -> dots += MapContent.Dot(l.from.coordinate, Color.White, ink.ink, 10f, 2f) }
        it.legs.firstOrNull()?.let { l -> dots += MapContent.Dot(l.from.coordinate, Color.White, ink.ink, 16f, 4f) }
        it.legs.lastOrNull()?.let { l -> dots += MapContent.Dot(l.to.coordinate, ink.ink, Color.White, 18f, 3f) }
        MapContent(it.legs.map { l -> MapContent.Line(l.mapPath, if (l.isTransit) lineStroke(l, ink.dark) else ahead, if (l.isTransit) 5f else 4f) }, dots)
    }
    val frame = remember(it.routeKey) { MapCamera.Fit(it.legs.flatMap { l -> l.mapPath + listOf(l.from.coordinate, l.to.coordinate) }) }
    PublitMap(content, frame, modifier, io.github.elija7580.publit.core.L10n.t("Trip overview"))
}
