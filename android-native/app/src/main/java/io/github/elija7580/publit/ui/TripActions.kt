package io.github.elija7580.publit.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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

/** Start, save, calendar, share and leave reminder for one route (TripActionsSection on iOS). */
@Composable
fun TripActions(store: TripStore, it: Itinerary, fromName: String, toName: String, onStarted: (SavedTrip) -> Unit) {
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

    Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(14.dp), color = ink.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val active = saved != null && state.activeId == saved.id
            if (active) {
                OutlinedButton(onClick = {
                    TripSession.stop(ctx, store, saved!!); reminderOn = false; note = L10n.t("Trip ended")
                }, Modifier.fillMaxWidth()) { Icon(Icons.Filled.StopCircle, null); Spacer(Modifier.width(8.dp)); Text(L10n.t("End trip")) }
            } else {
                val blocked = TripStatus.startBlocked(it, Instant.now())
                Button(onClick = { withNotifications { val t = ensureSaved(); TripSession.start(ctx, store, t); onStarted(t) } },
                    Modifier.fillMaxWidth().height(50.dp), enabled = blocked == null,
                    colors = ButtonDefaults.buttonColors(containerColor = ink.ink, contentColor = ink.onInk)) {
                    Text(L10n.t("Start trip"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                }
                blocked?.let { b -> Text(b, color = ink.muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Action(if (saved != null) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder,
                    L10n.t(if (saved != null) "Saved" else "Save trip"), Modifier.weight(1f)) {
                    if (saved != null) { TripSession.forget(ctx, store, saved) } else { store.save(it, fromName, toName); io.github.elija7580.publit.data.SmartRouting.learn(it) }
                }
                Action(Icons.Filled.CalendarMonth, L10n.t("Add to Calendar"), Modifier.weight(1f)) { openCalendar(ctx, ensureSaved()) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val upcoming = it.endTime > Instant.now()
                Action(Icons.Filled.Share, L10n.t(if (upcoming) "Share arrival" else "Share trip"), Modifier.weight(1f)) {
                    val link = io.github.elija7580.publit.data.ShareLinks.route(io.github.elija7580.publit.data.SharedRoute.of(it, fromName, toName))
                    val text = (if (upcoming) L10n.f("I'm arriving at %@ at %@.", toName, Fmt.time(it.endTime, it.legs.lastOrNull()?.to?.tz))
                        else "$fromName → $toName") + "\n" + link
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                if (it.startTime > Instant.now()) Action(if (reminderOn) Icons.Filled.Notifications else Icons.Filled.NotificationsNone,
                    L10n.t(if (reminderOn) "Reminder on" else "Leave reminder"), Modifier.weight(1f)) {
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
                } else Spacer(Modifier.weight(1f))
            }
            note?.let { n -> Text(n, color = ink.muted, fontSize = 13.sp) }
        }
    }
    Text(L10n.t("The calendar entry links back to this trip with its live status."), color = ink.muted, fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 28.dp))
}

@Composable
private fun Action(icon: ImageVector, title: String, modifier: Modifier, onClick: () -> Unit) {
    val ink = LocalInk.current
    Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(12.dp), color = ink.fill) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
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
    }

    fun stop(ctx: Context, store: TripStore, trip: SavedTrip) {
        store.stop(trip.id)
        TripNotifications.cancelLeave(ctx, trip.id)
        TripNotifications.cancelGetOff(ctx, trip.itinerary)
        TripNotifications.endLive(ctx)
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
            if (u.followLive == true || store.state.value.activeId == u.id) TripNotifications.showLive(ctx, u)
        }
        true
    } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { false }
}
