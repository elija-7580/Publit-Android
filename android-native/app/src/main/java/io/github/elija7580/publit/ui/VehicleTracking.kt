package io.github.elija7580.publit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.StopGrouping
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.data.TripProgress
import io.github.elija7580.publit.data.VehiclePositionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * "Track vehicle" (VehicleTrackingView on iOS): one departure's vehicle on a full map with its route and the
 * stops still to come. Refreshes by itself while open; "Follow vehicle" keeps it in the middle. The line under
 * the map says where the position comes from (live stop times or the timetable), or that there is none right now.
 */
@Composable
fun VehicleTrackingScreen(client: TransitousClient, target: TripTarget, onBack: () -> Unit) {
    val ink = LocalInk.current
    BackHandler(onBack = onBack)
    var leg by remember { mutableStateOf<Leg?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var follow by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<MapCamera?>(null) }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(5_000) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(target.stopTime.tripId) {
        // Only while the screen is resumed (battery, older phones).
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    client.trip(target.stopTime.tripId).legs.firstOrNull { it.isTransit }?.let { l -> leg = l; error = null }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
                delay(15_000)
            }
        }
    }
    val l = leg
    val stops = l?.allStops.orEmpty()
    val progress = TripProgress.at(now, stops)
    val shape = remember(l?.legGeometry?.points) { l?.legGeometry?.mapPoints.orEmpty() }
    val vehicle = TripProgress.position(progress, stops, shape)
    val source = VehiclePositionSource.of(progress, l?.realTime ?: false)
    // First the whole route; while following, the vehicle in the middle.
    val overview = remember(l == null) { l?.let { MapCamera.Fit(shape + stops.map { it.coordinate }) } }
    val shown = if (follow && vehicle != null) MapCamera.Around(vehicle, 1500.0) else camera ?: overview
    Column(Modifier.fillMaxSize().background(ink.ground).statusBarsPadding().navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Text(target.stopTime.lineName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (l != null && shown != null) {
                val stroke = lineStroke(l, ink.dark)
                PublitMap(MapContent(listOf(MapContent.Line(shape, stroke, 5f)),
                    stops.map { MapContent.Dot(it.coordinate, Color.White, stroke, 9f, 2f) } +
                        listOfNotNull(vehicle?.let { MapContent.Dot(it, stroke, hexColor(l.lineColors.text), 24f, 3f) })),
                    shown, Modifier.fillMaxSize(), L10n.t("Estimated vehicle position"),
                    // Moving the map by hand ends following.
                    onRegion = { r ->
                        if (follow && vehicle != null && r.center.distanceTo(vehicle) > r.latSpan * 111_000 * 0.2) {
                            follow = false; camera = MapCamera.Around(r.center, r.latSpan * 111_000)
                        }
                    })
                val enabled = vehicle != null
                Surface(Modifier.align(Alignment.TopEnd).padding(10.dp).alpha(if (enabled) 1f else 0.5f)
                    .clickable(enabled = enabled) { follow = !follow; if (!follow) camera = vehicle?.let { MapCamera.Around(it, 1500.0) } },
                    shape = RoundedCornerShape(50), color = ink.surface, shadowElevation = 2.dp) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (follow) Icons.Filled.GpsFixed else Icons.Filled.GpsNotFixed, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(L10n.t(if (follow) "Following vehicle" else "Follow vehicle"), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (error != null) Text(error!!, color = ink.muted, modifier = Modifier.padding(24.dp)) else CircularProgressIndicator(color = ink.ink)
            }
        }
        if (l != null) Surface(Modifier.fillMaxWidth().padding(10.dp), shape = RoundedCornerShape(18.dp), color = ink.surface) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tint = if (source == VehiclePositionSource.LIVE_TIMES) ink.live else ink.muted
                    Icon(when (source) { VehiclePositionSource.LIVE_TIMES -> Icons.Filled.Sensors; VehiclePositionSource.TIMETABLE -> Icons.Filled.CalendarMonth
                        else -> Icons.AutoMirrored.Filled.HelpOutline }, null, Modifier.size(16.dp), tint = tint)
                    Spacer(Modifier.width(6.dp))
                    Text(L10n.t(source.label), fontSize = 13.sp, color = tint)
                }
                val upcoming = VehiclePositionSource.upcoming(progress, stops)
                if (upcoming.isEmpty()) {
                    if (progress == TripProgress.Finished) Text(L10n.f("Arrived at %@", stops.lastOrNull()?.name ?: ""), fontSize = 15.sp)
                } else {
                    Text(L10n.t("Next stops"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink.muted)
                    val station = StopGrouping.normalizedName(target.stationName)
                    upcoming.take(4).forEach { s ->
                        Row {
                            Text(s.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                                fontWeight = if (StopGrouping.normalizedName(s.name) == station) FontWeight.Bold else FontWeight.Normal)
                            Spacer(Modifier.width(8.dp))
                            Text((s.arrival ?: s.departure)?.let { Fmt.time(it, s.tz) } ?: "–", fontSize = 15.sp)
                        }
                    }
                }
            }
        }
    }
}
