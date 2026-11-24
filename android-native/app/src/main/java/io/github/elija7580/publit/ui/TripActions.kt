package io.github.elija7580.publit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.TripStatus
import io.github.elija7580.publit.data.TripStore
import io.github.elija7580.publit.notify.TripNotifications
import java.time.Instant
import kotlinx.coroutines.launch

/** The route's actions in one bar pinned to the bottom of the route screen (TripActionBar on iOS): save, calendar,
 *  share and leave reminder as small square buttons, "Start trip" as the wide ink button; while the trip runs,
 *  share and a red "End trip". Stays one tap away while scrolling through long trips. */
@Composable
fun TripActionBar(store: TripStore, it: Itinerary, fromName: String, toName: String, onStarted: (SavedTrip) -> Unit) {
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val state by store.state.collectAsStateWithLifecycle()
    val saved = remember(state, it) { store.saved(it) }
    var note by remember { mutableStateOf<String?>(null) }
    var reminderOn by remember(saved?.id) { mutableStateOf(saved?.let { s -> TripNotifications.isLeaveScheduled(ctx, s.id) } == true) }
    fun ensureSaved() = saved ?: store.save(it, fromName, toName)
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { afterPermission?.invoke(); afterPermission = null }
    fun withNotifications(block: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 33 && !TripNotifications.allowed(ctx)) { afterPermission = block; permission.launch(Manifest.permission.POST_NOTIFICATIONS) } else block()
    }
    val active = saved != null && state.activeId == saved.id
    val blocked = if (active) null else TripStatus.startBlocked(it, Instant.now())
    val upcoming = it.endTime > Instant.now()
    val share = {
        val link = io.github.elija7580.publit.data.ShareLinks.route(io.github.elija7580.publit.data.SharedRoute.of(it, fromName, toName))
        val text = (if (upcoming) L10n.f("I'm arriving at %@ at %@.", toName, Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz))
            else "$fromName → $toName") + "\n" + link
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    Surface(Modifier.fillMaxWidth(), color = ink.surface) {
        Column {
            androidx.compose.material3.HorizontalDivider(color = ink.hairline)
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A grey button with no reason looked broken.
                (blocked ?: note)?.let { n -> Text(n, color = ink.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (active) {
                        BarIcon(Icons.Filled.Share, L10n.t(if (upcoming) "Share arrival" else "Share trip"), share)
                        Button(onClick = { TripSession.stop(ctx, store, saved!!); reminderOn = false; note = L10n.t("Trip ended") },
                            Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ink.danger, contentColor = androidx.compose.ui.graphics.Color.White)) {
                            Icon(Icons.Filled.StopCircle, null); Spacer(Modifier.width(8.dp)); Text(L10n.t("End trip"), fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    } else {
                        BarIcon(if (saved != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, L10n.t(if (saved != null) "Saved" else "Save trip")) {
                            if (saved != null) { TripSession.forget(ctx, store, saved) } else { store.save(it, fromName, toName); io.github.elija7580.publit.data.SmartRouting.learn(it) }
                        }
                        BarIcon(Icons.Filled.CalendarMonth, L10n.t("Add to Calendar")) { openCalendar(ctx, ensureSaved()) }
                        BarIcon(Icons.Filled.Share, L10n.t(if (upcoming) "Share arrival" else "Share trip"), share)
                        if (it.startTime > Instant.now()) BarIcon(if (reminderOn) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                            L10n.t(if (reminderOn) "Reminder on" else "Leave reminder")) {
                            val trip = ensureSaved()
                            if (reminderOn) {
                                TripNotifications.cancelLeave(ctx, trip.id)
                                if (state.activeId != trip.id) { store.setFollowLive(trip.id, false); TripNotifications.endLive(ctx) }
                                reminderOn = false; note = null
                            } else withNotifications {
                                note = when (TripNotifications.scheduleLeave(ctx, trip)) {
                                    TripNotifications.Result.DENIED -> L10n.t("Allow notifications for Publit in Settings.")
                                    TripNotifications.Result.TOO_LATE -> L10n.t("This trip starts now.")
                                    TripNotifications.Result.SCHEDULED -> {
                                        reminderOn = true; store.setFollowLive(trip.id, true)
                                        L10n.f("We'll remind you at %@.", Fmt.time(maxOf(TripStatus.reminderTime(trip.itinerary), Instant.now()), trip.itinerary.legs.firstOrNull()?.from?.tz))
                                    }
                                }
                            }
                        }
                        val walking = TripStatus.startsOnFoot(it, Instant.now())
                        Button(onClick = { withNotifications { val t = ensureSaved(); TripSession.start(ctx, store, t); onStarted(t) } },
                            Modifier.weight(1f).height(48.dp).semantics { contentDescription = L10n.t(if (walking) "Start walking navigation" else "Start trip") },
                            enabled = blocked == null, shape = RoundedCornerShape(14.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                            // Starting on foot: the button says so and opens the walk turn by turn (iOS #66).
                            Icon(if (walking) Icons.AutoMirrored.Filled.DirectionsWalk else Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(L10n.t(if (walking) "Start" else "Start trip"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** A square, icon-only action next to the start button; the title is read out by TalkBack. */
@Composable
private fun BarIcon(icon: ImageVector, title: String, onClick: () -> Unit) {
    val ink = LocalInk.current
    Surface(onClick = onClick, modifier = Modifier.size(48.dp).semantics { contentDescription = title },
        shape = RoundedCornerShape(14.dp), color = ink.fill, contentColor = ink.ink) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
    }
}

/** "Buy ticket" per operator (TicketSection on iOS). DB and FlixBus open on the user's connection. */
@Composable
fun TicketLinksCard(it: Itinerary) {
    val links = remember(it) { io.github.elija7580.publit.data.TicketLinks.links(it) }
    if (it.isDirect || links.isEmpty()) return
    val ink = LocalInk.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    fun open(url: String) = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(L10n.t("Tickets"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            links.forEach { l ->
                OutlinedButton(onClick = {
                    if (opening != null) return@OutlinedButton
                    if (l.search == null || l.search.shop == io.github.elija7580.publit.data.ShopSearch.Shop.DB) open(l.url)
                    else {
                        opening = l.url
                        scope.launch { open(io.github.elija7580.publit.data.TicketLinks.resolve(l)); opening = null }
                    }
                }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.ShoppingCart, null); Spacer(Modifier.width(8.dp))
                    Text(l.title, Modifier.weight(1f))
                    if (opening == l.url) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (links.any { l -> l.isPartner }) Text(L10n.t("Some ticket links are partner links: Publit may get a small commission. The price stays the same, and Publit sends nothing about you."),
                color = ink.muted, fontSize = 12.sp)
        }
    }
}

/** The calendar app's "New event" screen, prefilled (CalendarText on iOS; no permission needed). */
private fun openCalendar(ctx: Context, trip: SavedTrip) {
    val lines = trip.itinerary.transitLegs.map { it.lineName }
    val title = if (lines.isEmpty()) L10n.f("Walk to %@", trip.toName) else "${trip.toName} (${lines.joinToString(", ")})"
    val notes = trip.itinerary.transitLegs.joinToString("\n") { leg ->
        buildString {
            append(Fmt.time(leg.startTime, leg.from.tz)).append(' ').append(leg.lineName)
            leg.headsign?.takeIf { it.isNotEmpty() }?.let { append(" → ").append(it) }
            append(", ").append(leg.from.name)
            leg.from.track?.takeIf { it.isNotEmpty() }?.let { append(" (").append(L10n.f("Pl. %@", it)).append(")") }
            append(" – ").append(leg.to.name).append(' ').append(Fmt.time(leg.endTime, leg.to.tz))
        }
    }
    val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, title).putExtra(CalendarContract.Events.DESCRIPTION, notes)
        .putExtra(CalendarContract.Events.EVENT_LOCATION, trip.itinerary.transitLegs.firstOrNull()?.from?.name ?: trip.fromName)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, trip.startTime.toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, trip.endTime.toEpochMilli())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

/** Starting and ending a trip (TripSession on iOS): live notification, get-off reminders. */
object TripSession {
    fun start(ctx: Context, store: TripStore, trip: SavedTrip) {
        store.start(trip.id)
        TripNotifications.scheduleGetOff(ctx, trip.itinerary, trip.id)
        store.trip(trip.id)?.let { TripNotifications.showLive(ctx, it) }
        io.github.elija7580.publit.notify.GetOffService.sync(ctx)
    }

    fun stop(ctx: Context, store: TripStore, trip: SavedTrip) {
        store.stop(trip.id)
        TripNotifications.cancelLeave(ctx, trip.id)
        TripNotifications.cancelGetOff(ctx, trip.itinerary)
        TripNotifications.endLive(ctx)
        io.github.elija7580.publit.notify.GetOffAlerts.forget(trip.id)
    }

    fun forget(ctx: Context, store: TripStore, trip: SavedTrip) {
        stop(ctx, store, trip)
        store.remove(trip.id)
    }

    /** New live times for a saved trip, also for its reminders and live notification. False on failure. */
    suspend fun refresh(ctx: Context, client: io.github.elija7580.publit.data.TransitousClient, store: TripStore, t: SavedTrip): Boolean = try {
        val fresh = client.refresh(t.itinerary.id)
        store.update(t.id, fresh)
        store.trip(t.id)?.let { u ->
            TripNotifications.followLeave(ctx, t, u)
            TripNotifications.followGetOff(ctx, t.itinerary, u.itinerary, u.id)
            if (u.followLive == true || store.state.value.activeId == u.id) {
                TripNotifications.showLive(ctx, u)
                // Changes recomputed with the new live data; warns when one becomes unlikely.
                runCatching { io.github.elija7580.publit.notify.ChangeRiskNotifier.follow(ctx, u) }
            }
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
}
