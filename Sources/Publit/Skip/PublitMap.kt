// Publit for Android: the maps (OpenStreetMap via MapLibre). Swift builds a spec (AndroidMapView.swift),
// this side draws it and reports taps and the visible region back (DroidEvents.mapEvent).
package publit.module

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/// Colours and layer payloads for one map render, filled from Swift before the composer is made.
class MapSpec {
    val colors = mutableListOf<Any?>()
    val layers = mutableMapOf<String, Pair<Int, String>>()
    fun color(c: Any?) { colors.add(c) }
    fun layer(name: String, version: Int, json: String) { layers[name] = Pair(version, json) }
}

/// The map as Compose content for `ComposeView` (SkipUI ContentComposer).
class MapComposer(val mapId: String, val base: String, val spec: MapSpec) : skip.ui.ContentComposer {
    @Composable
    override fun Compose(context: skip.ui.ComposeContext) {
        val palette = spec.colors.map { c ->
            val color = c as? skip.ui.Color
            color?.colorImpl?.invoke()?.toArgb() ?: 0xFF4D4D59.toInt()
        }
        val ctx = LocalContext.current
        val holder = remember(mapId) { PublitMapHolder(mapId, ctx) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle, holder) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> holder.view.onStart()
                    Lifecycle.Event.ON_RESUME -> holder.view.onResume()
                    Lifecycle.Event.ON_PAUSE -> holder.view.onPause()
                    Lifecycle.Event.ON_STOP -> holder.view.onStop()
                    else -> {}
                }
            }
            lifecycle.addObserver(observer)
            holder.view.onStart(); holder.view.onResume()
            onDispose {
                lifecycle.removeObserver(observer)
                holder.destroy()
            }
        }
        AndroidView(factory = { holder.view }, modifier = context.modifier.fillMaxSize(),
                    update = { holder.apply(base, spec.layers, palette) })
    }
}

/// One MapLibre map and what it currently shows.
@SuppressLint("MissingPermission")
class PublitMapHolder(val mapId: String, val ctx: Context) {
    val view: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var styleKey = ""
    private var base = JSONObject()
    private var layers: Map<String, Pair<Int, String>> = emptyMap()
    private var palette: List<Int> = emptyList()
    private val applied = mutableMapOf<String, Int>()
    private var cameraVersion = -1
    private var recenter = -1
    private var poiShown: Boolean? = null
    private var tilted: Boolean? = null
    private val density = ctx.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private var destroyed = false
    private var vehicleAnimator: ValueAnimator? = null
    private val vehiclePos = mutableMapOf<String, LatLng>()
    private var vehicleFeatures: List<JSONObject> = emptyList()
    private var locationOn = false

    init {
        MapLibre.getInstance(ctx)
        view = MapView(ctx)
        view.onCreate(null)
        view.getMapAsync { m ->
            map = m
            m.uiSettings.isCompassEnabled = true
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isTiltGesturesEnabled = true
            m.addOnCameraIdleListener { reportRegion() }
            m.addOnMapClickListener { p -> tapped(p); true }
            m.addOnMapLongClickListener { p -> longPressed(p); true }
            refresh()
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        vehicleAnimator?.cancel()
        try { view.onPause(); view.onStop(); view.onDestroy() } catch (_: Throwable) {}
    }

    fun apply(baseJson: String, layers: Map<String, Pair<Int, String>>, palette: List<Int>) {
        this.base = try { JSONObject(baseJson) } catch (_: Throwable) { JSONObject() }
        this.layers = HashMap(layers)
        this.palette = palette
        refresh()
    }

    private fun refresh() {
        val m = map ?: return
        if (destroyed) return
        val key = styleKeyFor(base)
        if (key != styleKey) {
            styleKey = key
            style = null
            applied.clear(); poiShown = null; locationOn = false
            val builder = if (key.startsWith("{")) Style.Builder().fromJson(key) else Style.Builder().fromUri(key)
            m.setStyle(builder) { s ->
                if (styleKey != key) return@setStyle
                style = s
                setupLayers(s)
                refresh()
            }
            return
        }
        val s = style ?: return
        m.uiSettings.setAllGesturesEnabled(base.optBoolean("interactive", true))
        m.uiSettings.isCompassEnabled = base.optBoolean("controls", true)
        val poi = base.optBoolean("poi", false)
        if (poiShown != poi) {
            poiShown = poi
            for (l in s.layers) {
                val id = l.id
                if (id.startsWith("poi")) l.setProperties(PropertyFactory.visibility(if (poi && id != "poi_transit") Property.VISIBLE else Property.NONE))
            }
        }
        enableLocation(m, s)
        for ((name, entry) in layers) {
            if (applied[name] == entry.first) continue
            applied[name] = entry.first
            try { applyLayer(s, name, entry.second) } catch (t: Throwable) { android.util.Log.e("PublitMap", "layer $name", t) }
        }
        val tilt = base.optBoolean("tilt3d", false)
        if (tilted != tilt) {
            val first = tilted == null
            tilted = tilt
            if (!first || tilt) m.animateCamera(CameraUpdateFactory.tiltTo(if (tilt) 55.0 else 0.0))
        }
        val cam = base.optJSONObject("camera")
        if (cam != null && cam.optInt("v", 0) != cameraVersion) {
            val first = cameraVersion == -1
            cameraVersion = cam.optInt("v", 0)
            moveCamera(m, cam, animated = !first && cam.optBoolean("animated", true))
        }
        val rc = base.optInt("recenter", 0)
        if (recenter == -1) recenter = rc
        else if (rc != recenter) {
            recenter = rc
            follow(m, heading = false)
        }
    }

    // MARK: Styles

    private fun styleKeyFor(b: JSONObject): String {
        val dark = b.optBoolean("dark", false)
        return when (b.optString("style", "standard")) {
            "satellite", "imagery" -> satelliteStyle
            "muted" -> if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/positron"
            else -> if (dark) "https://tiles.openfreemap.org/styles/dark" else "https://tiles.openfreemap.org/styles/liberty"
        }
    }

    private val satelliteStyle = """
        {"version":8,"glyphs":"https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
         "sources":{"eox":{"type":"raster","tileSize":256,"maxzoom":17,
           "tiles":["https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{z}/{y}/{x}.jpg"],
           "attribution":"Sentinel-2 cloudless – https://s2maps.eu by EOX IT Services GmbH (Contains modified Copernicus Sentinel data 2020)"}},
         "layers":[{"id":"background","type":"background","paint":{"background-color":"#0b1a24"}},
                   {"id":"eox","type":"raster","source":"eox"}]}
    """.trimIndent()

    private fun firstSymbolLayer(s: Style): String? = s.layers.firstOrNull { it is SymbolLayer && !it.id.startsWith("pub-") }?.id

    private fun setupLayers(s: Style) {
        val empty = FeatureCollection.fromFeatures(emptyList())
        // Shapes arrive finely simplified; MapLibre thins them per zoom level (tiles are cut once, not on every zoom).
        s.addSource(GeoJsonSource("pub-lines", empty, GeoJsonOptions().withTolerance(0.5f).withBuffer(64)))
        s.addSource(GeoJsonSource("pub-dots", empty))
        s.addSource(GeoJsonSource("pub-stations", empty))
        s.addSource(GeoJsonSource("pub-rentals", empty, GeoJsonOptions().withCluster(true).withClusterRadius(45).withClusterMaxZoom(17)))
        s.addSource(GeoJsonSource("pub-markers", empty))
        s.addSource(GeoJsonSource("pub-vehicles", empty))
        val below = firstSymbolLayer(s)
        val casing = LineLayer("pub-lines-casing", "pub-lines").withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("cw"))),
            PropertyFactory.lineWidth(Expression.sum(Expression.get("w"), Expression.literal(3.0))),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineSortKey(Expression.get("z")))
        casing.setFilter(Expression.has("cw"))
        val lines = LineLayer("pub-lines-main", "pub-lines").withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("c"))),
            PropertyFactory.lineWidth(Expression.get("w")),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineSortKey(Expression.get("z")))
        if (below != null) { s.addLayerBelow(casing, below); s.addLayerBelow(lines, below) } else { s.addLayer(casing); s.addLayer(lines) }
        val dark = base.optBoolean("dark", false) || styleKey == satelliteStyle
        val textColor = if (dark) "#F2F2F2" else "#1C1C1E"
        val halo = if (dark) "#000000" else "#FFFFFF"
        s.addLayer(SymbolLayer("pub-stations", "pub-stations").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(false), PropertyFactory.iconIgnorePlacement(false),
            PropertyFactory.symbolSortKey(Expression.get("rank")),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(12f), PropertyFactory.textColor(textColor),
            PropertyFactory.textHaloColor(halo), PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 1.1f)),
            PropertyFactory.textOptional(true), PropertyFactory.textMaxWidth(9f)))
        s.addLayer(CircleLayer("pub-rental-clusters", "pub-rentals").withProperties(
            PropertyFactory.circleColor("#007A70"), PropertyFactory.circleRadius(16f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(1.5f)).also { it.setFilter(Expression.has("point_count")) })
        s.addLayer(SymbolLayer("pub-rental-count", "pub-rentals").withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textSize(12f),
            PropertyFactory.textColor("#FFFFFF"), PropertyFactory.textAllowOverlap(true)).also { it.setFilter(Expression.has("point_count")) })
        s.addLayer(SymbolLayer("pub-rentals", "pub-rentals").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(false),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textSize(11f),
            PropertyFactory.textColor(textColor), PropertyFactory.textHaloColor(halo), PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textOptional(true)).also { it.setFilter(Expression.not(Expression.has("point_count"))) })
        s.addLayer(CircleLayer("pub-dots", "pub-dots").withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("c"))),
            PropertyFactory.circleRadius(Expression.get("r")),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("b"))),
            PropertyFactory.circleStrokeWidth(Expression.get("bw")),
            PropertyFactory.circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP)))
        s.addLayer(SymbolLayer("pub-markers", "pub-markers").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.symbolSortKey(Expression.get("rank")),
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")), PropertyFactory.textSize(12f),
            PropertyFactory.textColor(textColor), PropertyFactory.textHaloColor(halo), PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 0.2f)),
            PropertyFactory.textOptional(true), PropertyFactory.textMaxWidth(10f)))
        s.addLayer(SymbolLayer("pub-vehicle-arrows", "pub-vehicles").withProperties(
            PropertyFactory.iconImage(Expression.get("arrow")),
            PropertyFactory.iconRotate(Expression.get("heading")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)).also { it.setFilter(Expression.has("heading")) })
        s.addLayer(SymbolLayer("pub-vehicles", "pub-vehicles").withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true)))
    }

    // MARK: Layers

    private fun color(v: Any?): String {
        val argb = when (v) {
            is Int -> palette.getOrNull(v) ?: 0xFF4D4D59.toInt()
            is Number -> palette.getOrNull(v.toInt()) ?: 0xFF4D4D59.toInt()
            is String -> parseHex(v)
            else -> 0xFF4D4D59.toInt()
        }
        return rgba(argb)
    }

    private fun argbOf(v: Any?): Int = when (v) {
        is Number -> palette.getOrNull(v.toInt()) ?: 0xFF4D4D59.toInt()
        is String -> parseHex(v)
        else -> 0xFF4D4D59.toInt()
    }

    private fun rgba(argb: Int): String {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        return "rgba(${(argb shr 16) and 0xFF},${(argb shr 8) and 0xFF},${argb and 0xFF},$a)"
    }

    private fun parseHex(hex: String): Int {
        val h = hex.trim().trimStart('#')
        return try {
            when (h.length) {
                6 -> (0xFF000000 or h.toLong(16)).toInt()
                // CSS order: RRGGBBAA
                8 -> { val v = h.toLong(16); (((v and 0xFF) shl 24) or (v ushr 8)).toInt() }
                else -> 0xFF4D4D59.toInt()
            }
        } catch (_: Throwable) { 0xFF4D4D59.toInt() }
    }

    private fun points(a: JSONArray?): List<Point> {
        if (a == null) return emptyList()
        val out = ArrayList<Point>(a.length())
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            out.add(Point.fromLngLat(p.optDouble(1), p.optDouble(0)))
        }
        return out
    }

    private fun applyLayer(s: Style, name: String, json: String) {
        val items = JSONArray(json)
        when (name) {
            "lines" -> {
                val features = ArrayList<Feature>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val pts = points(o.optJSONArray("pts"))
                    if (pts.size < 2) continue
                    val f = Feature.fromGeometry(LineString.fromLngLats(pts))
                    f.addStringProperty("c", color(o.opt("c")))
                    f.addNumberProperty("w", o.optDouble("w", 4.0))
                    f.addNumberProperty("z", i)
                    if (o.has("cw")) f.addStringProperty("cw", color(o.opt("cw")))
                    features.add(f)
                }
                s.getSourceAs<GeoJsonSource>("pub-lines")?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            "dots" -> {
                val features = ArrayList<Feature>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val f = Feature.fromGeometry(Point.fromLngLat(o.optDouble("lon"), o.optDouble("lat")))
                    f.addStringProperty("c", color(o.opt("c")))
                    f.addStringProperty("b", color(o.opt("b")))
                    f.addNumberProperty("r", o.optDouble("size", 12.0) / 2)
                    f.addNumberProperty("bw", o.optDouble("bw", 2.0))
                    features.add(f)
                }
                s.getSourceAs<GeoJsonSource>("pub-dots")?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            "markers" -> {
                val features = ArrayList<Feature>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val argb = argbOf(o.opt("c"))
                    val glyph = o.optString("glyph", "mappin")
                    val fg = if (o.has("fg")) argbOf(o.opt("fg")) else 0xFFFFFFFF.toInt()
                    val icon = "mk|$argb|$fg|$glyph"
                    if (s.getImage(icon) == null) s.addImage(icon, markerBitmap(argb, fg, glyph))
                    val f = Feature.fromGeometry(Point.fromLngLat(o.optDouble("lon"), o.optDouble("lat")))
                    f.addStringProperty("id", o.optString("id"))
                    f.addStringProperty("icon", icon)
                    f.addStringProperty("label", o.optString("title", ""))
                    f.addNumberProperty("rank", o.optInt("rank", i))
                    features.add(f)
                }
                s.getSourceAs<GeoJsonSource>("pub-markers")?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            "stations" -> {
                val features = ArrayList<Feature>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val major = o.optBoolean("major"); val pole = o.optBoolean("pole")
                    val size = if (major) 24 else if (pole) 16 else 18
                    val mode = o.optString("mode", "BUS")
                    val argb = parseHex(o.optString("hex", "4D4D59"))
                    val icon = "st|$mode|$size|$argb"
                    if (s.getImage(icon) == null) s.addImage(icon, stationBitmap(argb, mode, size))
                    val f = Feature.fromGeometry(Point.fromLngLat(o.optDouble("lon"), o.optDouble("lat")))
                    f.addStringProperty("id", o.optString("id"))
                    f.addStringProperty("icon", icon)
                    if (major || pole) f.addStringProperty("label", o.optString("label", ""))
                    f.addNumberProperty("rank", if (major) 0 else 1)
                    features.add(f)
                }
                s.getSourceAs<GeoJsonSource>("pub-stations")?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            "rentals" -> {
                val features = ArrayList<Feature>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val station = o.optBoolean("station")
                    val kind = o.optString("kind", "OTHER")
                    val icon = "rt|$kind|$station"
                    if (s.getImage(icon) == null) s.addImage(icon, rentalBitmap(kind, station))
                    val f = Feature.fromGeometry(Point.fromLngLat(o.optDouble("lon"), o.optDouble("lat")))
                    f.addStringProperty("id", o.optString("id"))
                    f.addStringProperty("icon", icon)
                    f.addStringProperty("label", o.optString("text", ""))
                    features.add(f)
                }
                s.getSourceAs<GeoJsonSource>("pub-rentals")?.setGeoJson(FeatureCollection.fromFeatures(features))
            }
            "vehicles" -> {
                val list = ArrayList<JSONObject>()
                for (i in 0 until items.length()) {
                    val o = items.getJSONObject(i)
                    val bg = parseHex(o.optString("bg", "4D4D59")); val fg = parseHex(o.optString("fg", "FFFFFF"))
                    val label = o.optString("label", "")
                    val icon = "vh|$label|$bg|$fg"
                    if (s.getImage(icon) == null) s.addImage(icon, vehicleBitmap(label, bg, fg))
                    // Like iOS: the badge sits in a box at least 44 wide; the arrow runs 4 outside it.
                    val r = max(vehicleWidth(label), dp(44f).toInt()) / 2 + dp(4f).toInt()
                    val dark = base.optBoolean("dark", false) || styleKey == satelliteStyle
                    val arrow = "ar|$r|$dark"
                    if (s.getImage(arrow) == null) s.addImage(arrow, arrowBitmap(r, dark))
                    o.put("icon", icon)
                    o.put("arrowKey", arrow)
                    list.add(o)
                }
                glideVehicles(s, list, base.optDouble("glide", 1.0))
            }
        }
    }

    private fun vehicleFeature(o: JSONObject, at: LatLng): Feature {
        val f = Feature.fromGeometry(Point.fromLngLat(at.longitude, at.latitude))
        f.addStringProperty("id", o.optString("id"))
        f.addStringProperty("icon", o.optString("icon"))
        f.addStringProperty("arrow", o.optString("arrowKey"))
        if (o.has("heading") && !o.isNull("heading")) f.addNumberProperty("heading", o.optDouble("heading"))
        return f
    }

    /// Vehicles glide to their new positions over the refresh interval, like on iOS.
    private fun glideVehicles(s: Style, list: List<JSONObject>, seconds: Double) {
        vehicleAnimator?.cancel()
        val from = HashMap(vehiclePos)
        val to = HashMap<String, LatLng>()
        for (o in list) to[o.optString("id")] = LatLng(o.optDouble("lat"), o.optDouble("lon"))
        vehicleFeatures = list
        val source = s.getSourceAs<GeoJsonSource>("pub-vehicles") ?: return
        fun frame(t: Float) {
            val features = ArrayList<Feature>(list.size)
            for (o in list) {
                val id = o.optString("id")
                val end = to[id] ?: continue
                val start = from[id]
                val p = if (start == null || t >= 1f) end else LatLng(start.latitude + (end.latitude - start.latitude) * t,
                                                                       start.longitude + (end.longitude - start.longitude) * t)
                vehiclePos[id] = p
                features.add(vehicleFeature(o, p))
            }
            source.setGeoJson(FeatureCollection.fromFeatures(features))
        }
        vehiclePos.keys.retainAll(to.keys)
        if (seconds <= 0.05 || from.isEmpty()) { frame(1f); return }
        var last = 0L
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = (min(seconds, 5.0) * 1000).toLong()
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener { a ->
            val now = System.currentTimeMillis()
            val t = a.animatedValue as Float
            if (t < 1f && now - last < 60) return@addUpdateListener
            last = now
            if (!destroyed) frame(t)
        }
        vehicleAnimator = anim
        anim.start()
    }

    // MARK: Camera

    private fun bounds(pts: List<LatLng>): LatLngBounds? {
        if (pts.isEmpty()) return null
        if (pts.size == 1) {
            val p = pts[0]
            return LatLngBounds.Builder().include(LatLng(p.latitude + 0.004, p.longitude + 0.006))
                .include(LatLng(p.latitude - 0.004, p.longitude - 0.006)).build()
        }
        val b = LatLngBounds.Builder()
        for (p in pts) b.include(p)
        return b.build()
    }

    private fun latLngs(a: JSONArray?): List<LatLng> {
        if (a == null) return emptyList()
        val out = ArrayList<LatLng>()
        for (i in 0 until a.length()) {
            val p = a.optJSONArray(i) ?: continue
            val lat = p.optDouble(0); val lon = p.optDouble(1)
            if (lat.isFinite() && lon.isFinite() && (lat != 0.0 || lon != 0.0)) out.add(LatLng(lat, lon))
        }
        return out
    }

    private fun moveCamera(m: MapLibreMap, cam: JSONObject, animated: Boolean) {
        if (view.width == 0 || view.height == 0) {
            view.post { if (!destroyed) moveCamera(m, cam, animated) }
            return
        }
        val pad = (cam.optDouble("padding", 48.0) * density).toInt()
        val update = when (cam.optString("type")) {
            "fit" -> bounds(latLngs(cam.optJSONArray("pts")))?.let {
                stopFollowing(m)
                CameraUpdateFactory.newLatLngBounds(it, pad, pad, pad, pad + (cam.optDouble("bottom", 0.0) * density).toInt())
            }
            "around", "center" -> {
                stopFollowing(m)
                val lat = cam.optDouble("lat"); val lon = cam.optDouble("lon")
                val meters = cam.optDouble("meters", 1600.0)
                val dLat = meters / 111_000.0 / 2
                val dLon = dLat / max(0.2, cos(Math.toRadians(lat)))
                val b = LatLngBounds.Builder().include(LatLng(lat + dLat, lon + dLon)).include(LatLng(lat - dLat, lon - dLon)).build()
                CameraUpdateFactory.newLatLngBounds(b, 0)
            }
            "follow" -> {
                if (follow(m, cam.optBoolean("heading", false))) null
                else bounds(latLngs(cam.optJSONArray("pts")))?.let { CameraUpdateFactory.newLatLngBounds(it, pad) }
            }
            else -> null
        } ?: return
        if (animated) m.animateCamera(update, 600) else m.moveCamera(update)
    }

    private fun stopFollowing(m: MapLibreMap) {
        if (locationOn) try { m.locationComponent.cameraMode = CameraMode.NONE } catch (_: Throwable) {}
    }

    /// Follows the user's position; false when location is not available (yet).
    private fun follow(m: MapLibreMap, heading: Boolean): Boolean {
        val s = style ?: return false
        enableLocation(m, s)
        if (!locationOn) return false
        return try {
            val lc = m.locationComponent
            lc.cameraMode = if (heading) CameraMode.TRACKING_COMPASS else CameraMode.TRACKING
            lc.renderMode = if (heading) RenderMode.COMPASS else RenderMode.NORMAL
            if (m.cameraPosition.zoom < 13) m.animateCamera(CameraUpdateFactory.zoomTo(15.0))
            true
        } catch (_: Throwable) { false }
    }

    private fun enableLocation(m: MapLibreMap, s: Style) {
        if (locationOn || !base.optBoolean("showsUser", true)) return
        if (Droid.locationPermission() == "denied" || Droid.locationPermission() == "notDetermined") return
        try {
            val lc = m.locationComponent
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, s).useDefaultLocationEngine(true).build())
            lc.isLocationComponentEnabled = true
            lc.renderMode = RenderMode.COMPASS
            locationOn = true
            if (base.optJSONObject("camera")?.optString("type") == "follow" || base.optBoolean("followAtStart", false)) {
                follow(m, base.optJSONObject("camera")?.optBoolean("heading", false) ?: false)
            }
        } catch (t: Throwable) { android.util.Log.w("PublitMap", "location", t) }
    }

    // MARK: Events

    private fun send(o: JSONObject) {
        try { DroidEvents.shared.mapEvent(mapId, o.toString()) } catch (t: Throwable) { android.util.Log.w("PublitMap", "event", t) }
    }

    private fun reportRegion() {
        val m = map ?: return
        val b = m.projection.visibleRegion.latLngBounds
        val o = JSONObject()
        o.put("t", "region")
        o.put("lat", b.center.latitude); o.put("lon", b.center.longitude)
        o.put("dlat", b.latitudeSpan); o.put("dlon", b.longitudeSpan)
        o.put("zoom", m.cameraPosition.zoom)
        send(o)
    }

    private fun tapped(p: LatLng) {
        val m = map ?: return
        val pt = m.projection.toScreenLocation(p)
        val r = 22 * density
        val box = RectF(pt.x - r, pt.y - r, pt.x + r, pt.y + r)
        for (layer in listOf("pub-vehicles", "pub-markers", "pub-rental-clusters", "pub-rentals", "pub-stations")) {
            val hit = try { m.queryRenderedFeatures(box, layer) } catch (_: Throwable) { emptyList() }
            val f = hit.firstOrNull() ?: continue
            if (layer == "pub-rental-clusters") {
                m.animateCamera(CameraUpdateFactory.newLatLngZoom(p, m.cameraPosition.zoom + 2))
                return
            }
            val o = JSONObject(); o.put("t", "select"); o.put("layer", layer.removePrefix("pub-")); o.put("id", f.getStringProperty("id") ?: "")
            send(o)
            return
        }
        if (poiShown == true) {
            val pois = try { m.queryRenderedFeatures(box).filter { it.hasProperty("name") && (it.hasProperty("class") || it.hasProperty("subclass")) } } catch (_: Throwable) { emptyList() }
            val f = pois.firstOrNull()
            if (f != null) {
                val g = f.geometry() as? Point
                val o = JSONObject(); o.put("t", "pick"); o.put("refined", true)
                o.put("lat", g?.latitude() ?: p.latitude); o.put("lon", g?.longitude() ?: p.longitude)
                o.put("name", f.getStringProperty("name"))
                o.put("category", if (f.hasProperty("subclass")) f.getStringProperty("subclass") else f.getStringProperty("class"))
                send(o)
                return
            }
        }
        val o = JSONObject(); o.put("t", "empty"); send(o)
    }

    private fun longPressed(p: LatLng) {
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        val o = JSONObject(); o.put("t", "pick"); o.put("refined", false); o.put("lat", p.latitude); o.put("lon", p.longitude)
        send(o)
        Thread {
            try {
                @Suppress("DEPRECATION")
                val a = Geocoder(ctx).getFromLocation(p.latitude, p.longitude, 1)?.firstOrNull() ?: return@Thread
                val r = JSONObject(); r.put("t", "pick"); r.put("refined", true); r.put("lat", p.latitude); r.put("lon", p.longitude)
                val street = listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ")
                r.put("name", if (street.isEmpty()) (a.featureName ?: "") else street)
                a.thoroughfare?.let { r.put("street", it) }
                a.subThoroughfare?.let { r.put("houseNumber", it) }
                a.locality?.let { r.put("locality", it) }
                a.countryCode?.let { r.put("country", it) }
                main.post { if (!destroyed) send(r) }
            } catch (_: Throwable) {}
        }.start()
    }

    // MARK: Icons

    private fun dp(v: Float) = v * density

    private fun drawVector(c: Canvas, v: ImageVector, cx: Float, cy: Float, size: Float, color: Int) {
        val scale = size / max(v.viewportWidth, v.viewportHeight)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG); paint.color = color; paint.style = Paint.Style.FILL
        c.save()
        c.translate(cx - v.viewportWidth * scale / 2, cy - v.viewportHeight * scale / 2)
        c.scale(scale, scale)
        fun walk(g: VectorGroup) {
            for (node in g) {
                when (node) {
                    is VectorPath -> c.drawPath(node.pathData.toPath().asAndroidPath(), paint)
                    is VectorGroup -> walk(node)
                }
            }
        }
        walk(v.root)
        c.restore()
    }

    private fun modeVector(mode: String): ImageVector = when (mode) {
        "BUS", "COACH", "FLEX" -> Icons.Filled.DirectionsBus
        "TRAM" -> Icons.Filled.Tram
        "SUBWAY", "METRO" -> Icons.Filled.Subway
        "FERRY" -> Icons.Filled.DirectionsBoat
        "AIRPLANE" -> Icons.Filled.Flight
        "FUNICULAR", "AERIAL_LIFT", "AREAL_LIFT", "CABLE_CAR" -> Icons.Filled.Cable
        "WALK" -> Icons.Filled.DirectionsWalk
        "BIKE", "RENTAL" -> Icons.Filled.PedalBike
        "CAR" -> Icons.Filled.DirectionsCar
        else -> Icons.Filled.Train
    }

    private fun glyphVector(name: String): ImageVector = when {
        name.startsWith("fork.knife") -> Icons.Filled.Restaurant
        name.startsWith("cup.and.saucer") -> Icons.Filled.LocalCafe
        name.startsWith("birthday.cake") -> Icons.Filled.Cake
        name.startsWith("cart") -> Icons.Filled.ShoppingCart
        name.startsWith("bag") -> Icons.Filled.ShoppingBag
        name.startsWith("cross.case") -> Icons.Filled.LocalPharmacy
        name.startsWith("cross") -> Icons.Filled.LocalHospital
        name.startsWith("bed") -> Icons.Filled.Hotel
        name.startsWith("banknote") -> Icons.Filled.LocalAtm
        name.startsWith("parkingsign") -> Icons.Filled.LocalParking
        name.startsWith("bolt.car") -> Icons.Filled.EvStation
        name.startsWith("fuelpump") -> Icons.Filled.LocalGasStation
        name.startsWith("toilet") -> Icons.Filled.Wc
        name.startsWith("building.columns") -> Icons.Filled.Museum
        name.startsWith("tree") -> Icons.Filled.Park
        name.startsWith("wineglass") -> Icons.Filled.WineBar
        name.startsWith("house") -> Icons.Filled.Home
        name.startsWith("briefcase") -> Icons.Filled.Work
        name.startsWith("building.2") -> Icons.Filled.LocationCity
        name.startsWith("tram") -> Icons.Filled.Tram
        name.startsWith("bus") -> Icons.Filled.DirectionsBus
        name.startsWith("train") -> Icons.Filled.Train
        name.startsWith("star") -> Icons.Filled.Star
        name.startsWith("flag") -> Icons.Filled.Flag
        else -> Icons.Filled.Place
    }

    private fun rentalVector(kind: String): ImageVector = when (kind) {
        "BICYCLE", "CARGO_BICYCLE" -> Icons.Filled.PedalBike
        "CAR" -> Icons.Filled.DirectionsCar
        "MOPED", "SCOOTER_SEATED" -> Icons.Filled.Moped
        "SCOOTER_STANDING" -> Icons.Filled.ElectricScooter
        else -> Icons.Filled.DirectionsWalk
    }

    private fun stationBitmap(argb: Int, mode: String, sizeDp: Int): Bitmap {
        val size = dp(sizeDp.toFloat())
        val bmp = Bitmap.createBitmap(size.toInt() + 2, size.toInt() + 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(1.5f, 1.5f, size - 0.5f, size - 0.5f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = argb
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG); stroke.color = 0xFFFFFFFF.toInt(); stroke.style = Paint.Style.STROKE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(rect, size * 0.3f, size * 0.3f, stroke)
        drawVector(c, modeVector(mode), (size + 2) / 2, (size + 2) / 2, size * 0.62f, 0xFFFFFFFF.toInt())
        return bmp
    }

    private fun markerBitmap(argb: Int, fg: Int, glyph: String): Bitmap {
        val w = dp(30f); val h = dp(40f)
        val bmp = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = argb
        val r = w / 2 - dp(1.5f)
        val path = android.graphics.Path()
        path.addCircle(w / 2, r + dp(1.5f), r, android.graphics.Path.Direction.CW)
        path.moveTo(w / 2 - r * 0.55f, r * 1.75f); path.lineTo(w / 2, h - dp(1f)); path.lineTo(w / 2 + r * 0.55f, r * 1.75f); path.close()
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG); shadow.color = 0x40000000
        c.drawCircle(w / 2, r + dp(2.5f), r, shadow)
        c.drawPath(path, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG); stroke.color = 0xFFFFFFFF.toInt(); stroke.style = Paint.Style.STROKE; stroke.strokeWidth = dp(1.5f)
        c.drawCircle(w / 2, r + dp(1.5f), r, stroke)
        drawVector(c, glyphVector(glyph), w / 2, r + dp(1.5f), r * 1.15f, fg)
        return bmp
    }

    private fun rentalBitmap(kind: String, station: Boolean): Bitmap {
        val s = dp(32f)
        val bmp = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = 0xFF007A70.toInt()
        val rect = RectF(dp(1f), dp(1f), s - dp(1f), s - dp(1f))
        val radius = if (station) dp(8f) else s / 2
        c.drawRoundRect(rect, radius, radius, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG); stroke.color = 0xFFFFFFFF.toInt(); stroke.style = Paint.Style.STROKE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(rect, radius, radius, stroke)
        drawVector(c, rentalVector(kind), s / 2, s / 2, dp(20f), 0xFFFFFFFF.toInt())
        return bmp
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.typeface = Typeface.DEFAULT_BOLD; it.textSize = 13 * density }

    private fun vehicleWidth(label: String): Int = min(dp(140f), max(dp(32f), badgePaint.measureText(label) + dp(12f))).toInt()

    private fun vehicleBitmap(label: String, bg: Int, fg: Int): Bitmap {
        val w = vehicleWidth(label); val h = dp(24f).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rect = RectF(dp(0.75f), dp(0.75f), w - dp(0.75f), h - dp(0.75f))
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = bg
        c.drawRoundRect(rect, dp(5f), dp(5f), fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG); stroke.color = 0xFFFFFFFF.toInt(); stroke.style = Paint.Style.STROKE; stroke.strokeWidth = dp(1.5f)
        c.drawRoundRect(rect, dp(5f), dp(5f), stroke)
        val text = Paint(badgePaint); text.color = fg; text.textAlign = Paint.Align.CENTER
        var t = label
        while (t.length > 1 && text.measureText(t) > w - dp(8f)) t = t.dropLast(1)
        val fm = text.fontMetrics
        c.drawText(t, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, text)
        return bmp
    }

    /// A small triangle on a ring of radius r around the badge; rotated by the heading.
    private fun arrowBitmap(r: Int, dark: Boolean): Bitmap {
        val size = 2 * r + dp(10f).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f; val top = size / 2f - r
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = if (dark) 0xFFFFFFFF.toInt() else 0xFF1C1C1E.toInt()
        val path = android.graphics.Path()
        path.moveTo(cx, top - dp(5f)); path.lineTo(cx + dp(4f), top + dp(2f)); path.lineTo(cx - dp(4f), top + dp(2f)); path.close()
        c.drawPath(path, p)
        return bmp
    }
}
