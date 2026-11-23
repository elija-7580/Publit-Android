// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.wear

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import org.maplibre.android.MapLibre
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
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * The map of the trip under way (WatchTripMapView on the Apple Watch): every leg in its line colour, walks dotted,
 * the stops to get on and off, and where you are. It opens on the leg under way. Tiles come from OpenFreeMap
 * (OpenStreetMap data); location runs only while this screen is open.
 */
class LiveMapActivity : ComponentActivity() {
    private lateinit var view: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        view = MapView(this)
        view.onCreate(savedInstanceState)
        setContentView(view)
        val trip = LiveTripStore.trip(this).value ?: run { finish(); return }
        view.getMapAsync { map ->
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.setAttributionMargins(0, 0, 0, 24)
            map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/dark")) { style ->
                if (isFinishing || isDestroyed) return@setStyle
                runCatching { draw(style, trip) }
                runCatching { follow(map, style) }
                frame(map, trip)
            }
        }
    }

    private fun draw(style: Style, trip: LiveTrip) {
        val lines = trip.legs.filter { it.points.size > 1 }.map { leg ->
            Feature.fromGeometry(LineString.fromLngLats(leg.points.map { (lat, lon) -> Point.fromLngLat(lon, lat) })).apply {
                addStringProperty("color", if (leg.walk) "#BBBBBB" else leg.color)
                addBooleanProperty("walk", leg.walk)
            }
        }
        // The ends of each ride: where to get on and off.
        val stops = trip.legs.filter { !it.walk && it.points.size > 1 }.flatMap { leg ->
            listOf(leg.points.first(), leg.points.last()).map { (lat, lon) ->
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply { addStringProperty("color", leg.color) }
            }
        }
        style.addSource(GeoJsonSource("trip-lines", FeatureCollection.fromFeatures(lines)))
        style.addSource(GeoJsonSource("trip-stops", FeatureCollection.fromFeatures(stops)))
        style.addLayer(LineLayer("trip-walks", "trip-lines").withFilter(Expression.eq(Expression.get("walk"), true)).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))), PropertyFactory.lineWidth(3f),
            PropertyFactory.lineDasharray(arrayOf(0.5f, 2f)), PropertyFactory.lineCap(Property.LINE_CAP_ROUND)))
        style.addLayer(LineLayer("trip-rides", "trip-lines").withFilter(Expression.eq(Expression.get("walk"), false)).withProperties(
            PropertyFactory.lineColor(Expression.toColor(Expression.get("color"))), PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
        style.addLayer(CircleLayer("trip-stops", "trip-stops").withProperties(
            PropertyFactory.circleColor("#FFFFFF"), PropertyFactory.circleRadius(4.5f),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("color"))), PropertyFactory.circleStrokeWidth(2.5f)))
    }

    /** Your position as a dot, only with location permission and only while the map is open. */
    @Suppress("MissingPermission")
    private fun follow(map: MapLibreMap, style: Style) {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return
        map.locationComponent.apply {
            activateLocationComponent(LocationComponentActivationOptions.builder(this@LiveMapActivity, style).build())
            isLocationComponentEnabled = true
            cameraMode = CameraMode.NONE
            renderMode = RenderMode.NORMAL
        }
    }

    /** The leg under way fills the round screen; without its shape, the whole trip. */
    private fun frame(map: MapLibreMap, trip: LiveTrip) {
        val leg = trip.legs.getOrNull(trip.current)?.points.orEmpty()
        val points = (if (leg.size > 1) leg else trip.legs.flatMap { it.points }).map { (lat, lon) -> LatLng(lat, lon) }
        when {
            points.size > 1 -> runCatching {
                val pad = (resources.displayMetrics.widthPixels * 0.18).toInt()
                map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(points).build(), pad))
            }
            points.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(points[0], 15.0))
        }
    }

    override fun onStart() { super.onStart(); view.onStart() }
    override fun onResume() { super.onResume(); view.onResume() }
    override fun onPause() { view.onPause(); super.onPause() }
    override fun onStop() { view.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); view.onLowMemory() }
    override fun onDestroy() { view.onDestroy(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); view.onSaveInstanceState(outState) }
}
