package io.github.elija7580.publit.ui

import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.DisposableEffect
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
    var details by remember { mutableStateOf(false) }
    if (state.activeId == tripId && !details) { LiveTripScreen(client, store, trip, error, onBack, onDetails = { details = true }, tickets = { tickets(trip) }); return }
    if (details) androidx.activity.compose.BackHandler { details = false }
    ItineraryDetail(trip.itinerary, { if (details) details = false else onBack() }, title = trip.toName) {
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

/** A started trip, step by step (LiveTripView on iOS): stage, cues (vibration, spoken directions), current ride, alternatives. */
/** Missed, off the path or cancelled: the next connections from where you are, to switch in one tap. */
@Composable
internal fun AlternativesCard(client: TransitousClient, store: TripStore, trip: SavedTrip, message: String, here: io.github.elija7580.publit.core.LatLon?) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    var options by remember { mutableStateOf<List<io.github.elija7580.publit.data.Itinerary>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val dest = trip.itinerary.legs.lastOrNull()?.to
    LaunchedEffect(here == null, trip.id) {
        val from = here ?: return@LaunchedEffect
        if (dest == null) return@LaunchedEffect
        options = runCatching { client.plan(from, dest.coordinate, null, false).itineraries.filter { o -> !o.isCancelled } }.getOrDefault(emptyList())
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, fontWeight = FontWeight.SemiBold, color = ink.delay)
            Text(L10n.f("Your trip: arrive %@", Fmt.time(trip.itinerary.endTime, dest?.tz)), color = ink.muted, fontSize = 13.sp)
            val list = options
            when {
                here == null -> {}
                list == null -> Text(L10n.t("Looking for the next connection…"), color = ink.muted, fontSize = 13.sp)
                list.isEmpty() -> Text(L10n.t("No other connection found right now."), color = ink.muted, fontSize = 13.sp)
                else -> {
                    (if (showAll) list.take(5) else list.take(1)).forEach { o ->
                        val first = o.transitLegs.firstOrNull()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(first?.let { f -> f.lineName + " · " + Fmt.time(f.startTime, f.from.tz) } ?: L10n.t("Walk"), fontWeight = FontWeight.SemiBold)
                                Text(listOfNotNull(first?.from?.name, L10n.f("Arrive %@", Fmt.time(o.endTime, o.legs.lastOrNull()?.to?.tz))).joinToString(" · "), color = ink.muted, fontSize = 13.sp)
                            }
                            androidx.compose.material3.TextButton({
                                TripNotifications_cancel(ctx, trip)
                                store.reroute(trip.id, o, L10n.t("Current location"))
                                store.trip(trip.id)?.let { t -> TripSession.start(ctx, store, t) }
                            }) { Text(L10n.t("Switch"), fontWeight = FontWeight.SemiBold, color = ink.ink) }
                        }
                    }
                    if (list.size > 1) androidx.compose.material3.TextButton({ showAll = !showAll }) { Text(L10n.t(if (showAll) "Fewer connections" else "Other connections"), color = ink.ink) }
                }
            }
        }
    }
}

private fun TripNotifications_cancel(ctx: android.content.Context, trip: SavedTrip) =
    io.github.elija7580.publit.notify.TripNotifications.cancelGetOff(ctx, trip.itinerary)

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
