package io.github.elija7580.publit.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Alert
import io.github.elija7580.publit.data.Alerts
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.StopGrouping
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.data.TripProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/** A departure opened as a trip (TripTarget on iOS). */
data class TripTarget(val stopTime: StopTime, val stationName: String)

/**
 * One vehicle's whole trip, as TripView on iOS: every stop with live times and the estimated position.
 * Map (milestone 6) and "Save line" (milestone 7) follow.
 */
@Composable
fun TripScreen(client: TransitousClient, target: TripTarget, onBack: () -> Unit,
               onRoute: (from: Place?, to: Place?) -> Unit) {
    val ink = LocalInk.current
    val stopTime = target.stopTime
    var trip by remember { mutableStateOf<Itinerary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var stops by remember { mutableStateOf(emptyList<Place>()) }
    var focus by remember { mutableStateOf(emptySet<Int>()) }
    var alerts by remember { mutableStateOf(emptyList<Alert>()) }
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(stopTime.tripId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                try {
                    val fresh = client.trip(stopTime.tripId)
                    val station = StopGrouping.normalizedName(target.stationName)
                    val leg = fresh.legs.firstOrNull { it.isTransit }
                    val all = leg?.allStops.orEmpty()
                    focus = all.indices.filter { StopGrouping.normalizedName(all[it].name) == station }.toSet()
                    alerts = Alerts.relevant(stopTime.serviceAlerts + leg?.serviceAlerts.orEmpty())
                    stops = all
                    trip = fresh
                    error = null
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    error = e.message ?: e.javaClass.simpleName
                }
                delay(30_000)
            }
        }
    }

    val leg = trip?.legs?.firstOrNull { it.isTransit }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = L10n.t("Back")) }
            Text(stopTime.lineName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            item("head") {
                Card {
                    DepartureRow(stopTime, now)
                    (if (trip == null) stopTime.serviceAlerts else alerts).forEach {
                        HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(it.headerText, color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            if (it.descriptionText.isNotBlank()) Text(it.descriptionText, color = ink.muted, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (leg != null) {
                val progress = TripProgress.at(now, stops)
                item("status") {
                    Card {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (leg.realTime) Icons.Filled.Sensors else Icons.Filled.CalendarMonth, null,
                                    Modifier.size(16.dp), tint = if (leg.realTime) ink.live else ink.muted)
                                Spacer(Modifier.size(6.dp))
                                Text(statusText(progress, stops), fontSize = 15.sp, color = if (leg.realTime) ink.live else ink.muted)
                            }
                            Text(L10n.t(if (leg.realTime) "Position estimated from live stop times."
                                else "No live data for this trip. Position estimated from the timetable."), fontSize = 12.sp, color = ink.muted)
                        }
                    }
                }
                item("stopsTitle") {
                    Text(L10n.t("Stops"), color = ink.muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 28.dp))
                }
                item("stops") {
                    Card {
                        stops.forEachIndexed { i, stop ->
                            if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                StopTimeRow(stop, i in focus, isPast(i, progress), leg.realTime, Modifier.clickable { menu = true })
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (i !in focus) DropdownMenuItem(text = { Text(L10n.f("From %@ to here", target.stationName)) },
                                        onClick = { menu = false; onRoute(stopTime.place.copy(name = target.stationName), stop) })
                                    DropdownMenuItem(text = { Text(L10n.t("Route here")) }, onClick = { menu = false; onRoute(null, stop) })
                                    DropdownMenuItem(text = { Text(L10n.t("Route from here")) }, onClick = { menu = false; onRoute(stop, null) })
                                }
                            }
                        }
                    }
                }
            } else item("state") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    if (error != null) Text(error!!, color = ink.muted) else CircularProgressIndicator(color = ink.ink)
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = LocalInk.current.surface) {
        Column { content() }
    }
}

private fun isPast(i: Int, p: TripProgress): Boolean = when (p) {
    TripProgress.Finished -> true
    TripProgress.NotStarted -> false
    is TripProgress.AtStop -> i < p.index
    is TripProgress.Between -> i <= p.index
}

private fun statusText(p: TripProgress, stops: List<Place>): String = when (p) {
    TripProgress.NotStarted -> L10n.t("Not departed yet")
    TripProgress.Finished -> L10n.f("Arrived at %@", stops.lastOrNull()?.name ?: "")
    is TripProgress.AtStop -> L10n.f("At %@", stops.getOrNull(p.index)?.name ?: "")
    is TripProgress.Between -> {
        val a = stops.getOrNull(p.index); val b = stops.getOrNull(p.index + 1)
        if (a == null || b == null) L10n.t("Not departed yet") else L10n.f("Between %@ and %@", a.name, b.name)
    }
}

@Composable
private fun StopTimeRow(stop: Place, isFocus: Boolean, isPast: Boolean, realTime: Boolean, modifier: Modifier) {
    val ink = LocalInk.current
    val time = stop.departure ?: stop.arrival
    val scheduled = stop.scheduledDeparture ?: stop.scheduledArrival
    val delay = if (time != null && scheduled != null) Math.round((time.epochSecond - scheduled.epochSecond) / 60.0).toInt() else 0
    val color = if (isPast) ink.muted else ink.ink
    val weight = if (isFocus) FontWeight.Bold else FontWeight.Normal
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(time?.let { Fmt.time(it, stop.tz) } ?: "–", fontSize = 15.sp, fontWeight = weight, color = color,
            modifier = Modifier.widthIn(min = 52.dp), maxLines = 1)
        if (realTime && delay > 0) Text("+$delay", fontSize = 12.sp, color = ink.delay)
        Text(stop.name, fontSize = 15.sp, fontWeight = weight, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textDecoration = if (stop.cancelled == true) TextDecoration.LineThrough else null, modifier = Modifier.weight(1f))
        stop.track?.takeIf { it.isNotEmpty() }?.let { Text(L10n.f("Pl. %@", it), fontSize = 12.sp, color = ink.muted) }
    }
}
