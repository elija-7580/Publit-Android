package io.github.elija7580.publit.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.data.TripProgress
import io.github.elija7580.publit.data.TripStatus
import io.github.elija7580.publit.data.TripStore
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A saved trip with its live status (SavedTripView on iOS); a started trip shows the live guide. */
@Composable
fun SavedTripScreen(client: TransitousClient, store: TripStore, tripId: String, onBack: () -> Unit, tickets: @Composable (SavedTrip) -> Unit = {}) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val trip = state.trips.firstOrNull { it.id == tripId }
    var error by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(tripId) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val t = store.trip(tripId) ?: break
                if (t.endTime.plusSeconds(15 * 60) <= Instant.now()) break
                error = if (TripSession.refresh(ctx, client, store, t)) null else L10n.t("Live status unavailable. Showing saved times.")
                delay(60_000)
            }
        }
    }
    if (trip == null) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(L10n.t("Trip not found"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                Text(L10n.t("It was removed from this device."), color = ink.muted)
            }
        }
        return
    }
    if (state.activeId == tripId) { LiveTripScreen(store, trip, error, onBack); return }
    ItineraryDetail(trip.itinerary, onBack, title = trip.toName) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OfflineNote(client)
            StatusCard(trip, error)
            TripActions(store, trip.itinerary, trip.fromName, trip.toName) { }
            tickets(trip)
        }
    }
}

@Composable
private fun StatusCard(trip: SavedTrip, error: String?) {
    val ink = LocalInk.current
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(15_000) } }
    val s = TripStatus.at(now, trip.itinerary)
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.headline, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = if (s.phase == TripStatus.Phase.CANCELLED) ink.danger else ink.ink)
                if (s.delayMinutes > 0) { Spacer(Modifier.width(8.dp)); Text(L10n.f("+%lld min", s.delayMinutes), color = ink.delay, fontWeight = FontWeight.SemiBold) }
            }
            if (s.detail.isNotEmpty()) Text(s.detail, color = ink.muted, fontSize = 14.sp)
            trip.refreshedAt?.let { Text(L10n.f("Updated %@", Fmt.time(it)), color = ink.muted, fontSize = 12.sp) }
            error?.let { Text(it, color = ink.delay, fontSize = 12.sp) }
        }
    }
}

/** A started trip, step by step (LiveTripView on iOS, condensed): status, current ride, stops left, map. */
@Composable
fun LiveTripScreen(store: TripStore, trip: SavedTrip, error: String?, onBack: () -> Unit) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val now by produceState(Instant.now()) { while (true) { value = Instant.now(); delay(5_000) } }
    val it = trip.itinerary
    val s = TripStatus.at(now, it)
    val legIndex = it.legs.indexOfFirst { l -> now < l.endTime }.let { i -> if (i < 0) it.legs.lastIndex else i }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, L10n.t("Back")) }
                Text(trip.toName, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Column {
                    RouteMap(it, Modifier.fillMaxWidth().height(220.dp))
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.headline, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                            color = if (s.phase == TripStatus.Phase.CANCELLED) ink.danger else ink.ink)
                        if (s.detail.isNotEmpty()) Text(s.detail, color = ink.muted, fontSize = 15.sp)
                        when {
                            s.phase == TripStatus.Phase.CANCELLED -> Text(L10n.t("This trip is cancelled."), color = ink.danger, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            s.delayMinutes > 0 -> Text(L10n.f("+%lld min", s.delayMinutes), color = ink.delay, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            it.transitLegs.any { l -> l.realTime } -> Text(L10n.t("On time"), color = ink.live, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        }
                        error?.let { e -> Text(e, color = ink.delay, fontSize = 12.sp) }
                    }
                }
            }
        }
        val leg = it.legs.getOrNull(legIndex)
        if (leg != null && leg.isTransit && s.phase == TripStatus.Phase.RIDING) item {
            val stops = leg.allStops
            val progress = TripProgress.at(now, stops)
            val next = TripProgress.nextStopIndex(progress, stops.size)
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Column(Modifier.padding(vertical = 8.dp)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(leg); Spacer(Modifier.width(8.dp))
                        val left = next?.let { n -> stops.size - 1 - n } ?: 0
                        Text(if (left == 0) L10n.t("Next stop") else L10n.f("in %@", if (left == 1) L10n.t("1 stop") else L10n.f("%lld stops", left)),
                            fontWeight = FontWeight.SemiBold)
                    }
                    stops.forEachIndexed { i, p ->
                        if (next != null && i < next) return@forEachIndexed
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(10.dp).background(if (i == stops.lastIndex) ink.ink else ink.hairline, CircleShape))
                            Spacer(Modifier.width(12.dp))
                            Text((p.arrival ?: p.departure)?.let { t -> Fmt.time(t, p.tz) } ?: "–", fontSize = 14.sp, modifier = Modifier.width(52.dp))
                            Text(p.name, fontSize = 15.sp, fontWeight = if (i == stops.lastIndex) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                        }
                    }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
                Column {
                    it.legs.forEachIndexed { i, l ->
                        if (i > 0) HorizontalDivider(color = ink.hairline, modifier = Modifier.padding(horizontal = 16.dp))
                        val done = i < legIndex
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (l.isTransit) LineBadge(l) else Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = ink.muted)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (l.isTransit) (l.shownDirection ?: l.to.name) else L10n.f("Walk to %@", l.to.name),
                                    color = if (done) ink.muted else ink.ink, fontWeight = if (i == legIndex) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                                Text(Fmt.time(l.startTime, l.from.tz) + " – " + Fmt.time(l.endTime, l.to.tz), color = ink.muted, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        item {
            val arrived = now >= it.endTime
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (arrived) L10n.t("You have arrived") else L10n.f("Arrive at %@", trip.toName) + " · " + Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
                Button(onClick = { TripSession.stop(ctx, store, trip) }, Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                    Text(L10n.t("End trip"), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** One saved trip in a list (SavedTripRow on iOS). */
@Composable
fun SavedTripRow(trip: SavedTrip, faded: Boolean = false) {
    val ink = LocalInk.current
    val tz = trip.itinerary.legs.firstOrNull()?.from?.tz
    val day = DateTimeFormatter.ofPattern("EEE d MMM", L10n.locale).format(trip.startTime.atZone(Fmt.zone(tz)))
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val c = if (faded) ink.muted else ink.ink
        Row {
            Text(trip.toName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = c, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(day, color = ink.muted, fontSize = 14.sp)
        }
        Text(Fmt.time(trip.startTime, tz) + " – " + Fmt.time(trip.endTime, trip.itinerary.legs.lastOrNull()?.to?.tz), fontSize = 14.sp, color = c)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { trip.itinerary.transitLegs.take(4).forEach { LineBadge(it) } }
        Text(L10n.f("from %@", trip.fromName), color = ink.muted, fontSize = 12.sp, maxLines = 1)
    }
}
