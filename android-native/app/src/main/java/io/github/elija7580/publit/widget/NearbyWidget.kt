// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.widget

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.data.StopGroup
import io.github.elija7580.publit.data.StopGrouping
import io.github.elija7580.publit.data.StopTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Departures at the stops closest to you (NearbyWidget on iOS, Publit#19). Saved stops go first when
 * you are near them. Its position comes from the app's location access; it refreshes about every 15
 * minutes and when it is tapped open.
 */
class NearbyWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = load(context)
        val texts = JSONObject().put("title", L10n.t("Departures nearby")).put("noLocation", L10n.t("Location needed"))
            .put("noLocationDetail", L10n.t("Allow location for Publit's widgets in Settings."))
            .put("unavailable", L10n.t("Departures unavailable")).put("noneSoon", L10n.t("No departures soon"))
        provideContent { Content(context, data, texts) }
    }

    companion object {
        /** Saved stops this close (metres) go first (iOS `NearbyBoard.favoriteRadius`). */
        const val FAVORITE_RADIUS = 600.0

        suspend fun refreshAll(context: Context) = NearbyWidget().updateAll(context)

        /** Closest first, but saved stops within `FAVORITE_RADIUS` before all others (pure, unit-tested). */
        fun ordered(groups: List<StopGroup>, saved: List<SavedPlace>): List<Pair<StopGroup, Boolean>> {
            val stops = saved.filter { it.isStop }
            fun favorite(g: StopGroup) = (g.distance ?: Double.MAX_VALUE) <= FAVORITE_RADIUS &&
                stops.any { s -> s.stopId in g.stopIds || (StopGrouping.normalizedName(s.name) == StopGrouping.normalizedName(g.name) &&
                    s.coordinate.distanceTo(g.coordinate) < StopGrouping.MERGE_RADIUS) }
            return groups.map { it to favorite(it) }.sortedWith(compareBy<Pair<StopGroup, Boolean>> { if (it.second) 0 else 1 }
                .thenBy { it.first.distance ?: Double.MAX_VALUE })
        }

        @SuppressLint("MissingPermission")
        private suspend fun here(context: Context): LatLon? {
            val app = context.applicationContext as io.github.elija7580.publit.PublitApplication
            val access = app.location.access()
            if (access != io.github.elija7580.publit.location.LocationAccess.PRECISE && access != io.github.elija7580.publit.location.LocationAccess.APPROXIMATE) return null
            withTimeoutOrNull(6_000) { runCatching { app.location.fixes.first() }.getOrNull() }?.let { return it.coordinate }
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            return runCatching {
                manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
            }.getOrNull()?.let { LatLon(it.latitude, it.longitude) }
        }

        private suspend fun load(context: Context): JSONObject = try {
            val here = here(context)
            if (here == null) JSONObject().put("state", "noLocation") else {
                val app = context.applicationContext as io.github.elija7580.publit.PublitApplication
                var res = io.github.elija7580.publit.data.StopTimesResponse()
                for (r in io.github.elija7580.publit.data.Reach.nearbyRadii) {
                    if (res.stopTimes.isNotEmpty()) break
                    res = app.client.departures(here, r, 60)
                }
                val now = Instant.now()
                val groups = StopGrouping.group(StopTime.upcoming(res.stopTimes, now), here)
                val list = JSONArray()
                for ((g, fav) in ordered(groups, app.favorites.state.value.items).take(3)) {
                    val rows = JSONArray()
                    for (st in g.departures.take(4)) {
                        val m = Math.ceil((st.departureTime.epochSecond - now.epochSecond) / 60.0).toInt()
                        val text = when { m <= 0 -> L10n.t("now"); m < 60 -> L10n.f("%lld min", m); else -> Fmt.time(st.departureTime, st.place.tz) }
                        val c = st.lineColors
                        rows.put(JSONObject().put("line", st.lineName).put("background", c.background).put("foreground", c.text)
                            .put("headsign", (if (st.isCircular) "↻ " else "") + (st.shownDirection ?: "")).put("cancelled", st.isCancelled)
                            .put("time", st.departureTime.toEpochMilli()).put("timeText", text))
                    }
                    list.put(JSONObject().put("name", g.name).put("stopId", g.id).put("favorite", fav)
                        .put("distance", g.distance?.let { Fmt.distance(it) } ?: "").put("rows", rows))
                }
                JSONObject().put("state", "ok").put("stops", list)
            }
        } catch (e: Throwable) { android.util.Log.e("Publit", "nearby widget failed", e); JSONObject().put("state", "failed") }

        fun open(context: Context, stop: JSONObject?): Intent =
            if (stop == null) Intent(context, io.github.elija7580.publit.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else Intent(context, io.github.elija7580.publit.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("stopId", stop.optString("stopId")).putExtra("stopName", stop.optString("name"))
                .setData(Uri.parse("publit-widget://nearby/" + Uri.encode(stop.optString("stopId"))))
    }
}

class NearbyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NearbyWidget()
}

private fun color(context: Context, name: String, fallback: Color): ColorProvider {
    val id = context.resources.getIdentifier(name, "color", context.packageName)
    return if (id != 0) ColorProvider(id) else ColorProvider(fallback)
}

@Composable
private fun Content(context: Context, data: JSONObject, texts: JSONObject) {
    val ink = color(context, "publit_ink", Color(0xFF111111))
    val ground = color(context, "publit_ground", Color(0xFFF4F4F2))
    val danger = ColorProvider(Color(0xFFD7263D))
    val size = LocalSize.current
    Column(GlanceModifier.fillMaxSize().background(ground).cornerRadius(18.dp).padding(12.dp)
        .clickable(actionStartActivity(NearbyWidget.open(context, null)))) {
        val state = data.optString("state")
        val stops = data.optJSONArray("stops")
        if (state != "ok" || stops == null || stops.length() == 0) {
            Text("●", style = TextStyle(color = color(context, "publit_signature", Color(0xFFFFC531)), fontSize = 18.sp))
            Text(texts.optString(if (state == "noLocation") "noLocation" else "title"), maxLines = 2,
                style = TextStyle(color = ink, fontSize = 14.sp, fontWeight = FontWeight.Bold))
            Text(texts.optString(when (state) { "noLocation" -> "noLocationDetail"; "failed" -> "unavailable"; else -> "noneSoon" }), maxLines = 3,
                style = TextStyle(color = ink, fontSize = 12.sp))
            return@Column
        }
        // Each stop takes a name line and its rows; as many as fit.
        val perStop = if (size.height < 200.dp) 2 else 3
        val room = maxOf(1, ((size.height.value - 24) / (22 + perStop * 22)).toInt())
        val now = System.currentTimeMillis()
        for (i in 0 until minOf(room, stops.length())) {
            val stop = stops.getJSONObject(i)
            if (i > 0) Spacer(GlanceModifier.height(8.dp))
            Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity(NearbyWidget.open(context, stop)))) {
                Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (stop.optBoolean("favorite")) Text("★ ", style = TextStyle(color = ColorProvider(Color(0xFFFFC531)), fontSize = 12.sp))
                    Text(stop.optString("name"), maxLines = 1, modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = ink, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    Text(stop.optString("distance"), maxLines = 1, style = TextStyle(color = ink, fontSize = 11.sp))
                }
                val rows = stop.optJSONArray("rows") ?: JSONArray()
                val shown = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optDouble("time") > now - 30_000 }.take(perStop)
                for (d in shown) {
                    Spacer(GlanceModifier.height(4.dp))
                    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(d)
                        Spacer(GlanceModifier.width(6.dp))
                        Text(d.optString("headsign"), maxLines = 1, modifier = GlanceModifier.defaultWeight(), style = TextStyle(color = ink, fontSize = 12.sp))
                        Text(d.optString("timeText"), maxLines = 1, style = TextStyle(
                            color = if (d.optBoolean("cancelled")) danger else ink, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            textDecoration = if (d.optBoolean("cancelled")) TextDecoration.LineThrough else TextDecoration.None))
                    }
                }
            }
        }
    }
}
