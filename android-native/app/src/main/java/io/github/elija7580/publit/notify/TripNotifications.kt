package io.github.elija7580.publit.notify

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import io.github.elija7580.publit.MainActivity
import io.github.elija7580.publit.PublitApplication
import io.github.elija7580.publit.R
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.TripStatus
import java.time.Instant

/**
 * Local notifications, as LeaveReminder, GetOffReminder and the Live Activity on iOS. Nothing
 * leaves the device: alarms fire a receiver that posts the notification; the live trip is an
 * ongoing notification refreshed at each next event (no background service).
 */
object TripNotifications {
    private const val REMINDERS = "reminders"
    private const val LIVE = "live"
    const val EXTRA_TRIP = "trip"
    private const val PREFS = "publit.reminders"
    private const val LIVE_ID = 7001

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(REMINDERS, L10n.t("Reminders"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(LIVE, L10n.t("Live trip"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    fun allowed(ctx: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // Leave reminder

    fun leaveKey(tripId: String) = "leave|$tripId"

    enum class Result { SCHEDULED, TOO_LATE, DENIED }

    fun scheduleLeave(ctx: Context, trip: SavedTrip): Result {
        if (trip.startTime <= Instant.now().plusSeconds(30)) return Result.TOO_LATE
        if (!allowed(ctx)) return Result.DENIED
        val fire = maxOf(TripStatus.reminderTime(trip.itinerary), Instant.now().plusSeconds(5))
        val status = TripStatus.at(fire, trip.itinerary)
        val title = L10n.f("Leave at %@ for %@", Fmt.time(trip.startTime, trip.itinerary.legs.firstOrNull()?.from?.tz), trip.toName)
        schedule(ctx, leaveKey(trip.id), fire, title, status.detail, trip.id)
        return Result.SCHEDULED
    }

    fun isLeaveScheduled(ctx: Context, tripId: String) = pending(ctx).contains(leaveKey(tripId))
    fun cancelLeave(ctx: Context, tripId: String) = cancel(ctx, leaveKey(tripId))

    /** Moves a pending reminder when live times shifted the departure. */
    fun followLeave(ctx: Context, old: SavedTrip, new: SavedTrip) {
        if (TripStatus.reminderMoved(old.itinerary, new.itinerary) && isLeaveScheduled(ctx, new.id)) scheduleLeave(ctx, new)
    }

    // Get-off reminders

    private const val GET_OFF_LEAD = 120L
    fun getOffKey(leg: Leg) = "getoff|${leg.tripId ?: leg.lineName}|${leg.to.name}|${leg.scheduledEnd.epochSecond}"

    /** A started trip: a reminder for every ride, only when notifications are allowed. */
    fun scheduleGetOff(ctx: Context, it: Itinerary, tripId: String?) {
        if (!allowed(ctx)) return
        for (leg in it.transitLegs) {
            val fire = maxOf(leg.endTime.minusSeconds(GET_OFF_LEAD), leg.startTime)
            if (fire <= Instant.now().plusSeconds(5)) continue
            schedule(ctx, getOffKey(leg), fire, L10n.t("Get off soon"),
                L10n.f("%@ reaches %@ at %@.", leg.lineName, leg.to.name, Fmt.time(leg.endTime, leg.to.tz)), tripId)
        }
    }
    /** "Remind me to get off" for one ride (GetOffButton on iOS). Null when the stop is less than two minutes away. */
    fun scheduleGetOff(ctx: Context, leg: Leg): Instant? {
        val fire = maxOf(leg.endTime.minusSeconds(GET_OFF_LEAD), leg.startTime)
        if (fire <= Instant.now().plusSeconds(5)) return null
        schedule(ctx, getOffKey(leg), fire, L10n.t("Get off soon"),
            L10n.f("%@ reaches %@ at %@.", leg.lineName, leg.to.name, Fmt.time(leg.endTime, leg.to.tz)), null)
        return fire
    }
    fun isGetOffSet(ctx: Context, leg: Leg) = pending(ctx).contains(getOffKey(leg))

    fun cancelGetOff(ctx: Context, it: Itinerary) = it.transitLegs.forEach { cancel(ctx, getOffKey(it)) }

    /** A live refresh moved an arrival: a pending reminder moves with it. */
    fun followGetOff(ctx: Context, old: Itinerary, new: Itinerary, tripId: String) {
        if (old.legs.size != new.legs.size) return
        old.legs.zip(new.legs).forEach { (a, b) ->
            if (b.isTransit && kotlin.math.abs(b.endTime.epochSecond - a.endTime.epochSecond) >= 60 &&
                getOffKey(a) == getOffKey(b) && pending(ctx).contains(getOffKey(a))) {
                val fire = maxOf(b.endTime.minusSeconds(GET_OFF_LEAD), b.startTime)
                if (fire > Instant.now().plusSeconds(5)) schedule(ctx, getOffKey(b), fire, L10n.t("Get off soon"),
                    L10n.f("%@ reaches %@ at %@.", b.lineName, b.to.name, Fmt.time(b.endTime, b.to.tz)), tripId)
            }
        }
    }

    // Live trip (the Live Activity on iOS): an ongoing notification.

    /**
     * The live trip as an ongoing notification (TripLiveActivity on iOS): line, what to do now, a countdown,
     * the platform (orange when it changed), "Then: …", the stages with their stops and times, and
     * "Show ticket" / "End trip". On Android 16 it is a Live Update: a progress bar per leg in the
     * line colours with a point at each change, shown as a chip in the status bar.
     */
    fun showLive(ctx: Context, trip: SavedTrip, now: Instant = Instant.now()) {
        if (!allowed(ctx)) return
        val it = trip.itinerary
        val s = TripStatus.at(now, it, countdown = false)
        if (s.phase == TripStatus.Phase.ARRIVED && now > trip.endTime.plusSeconds(300)) { endLive(ctx); return }
        val legs = it.legs
        val leg = legs.firstOrNull { l -> l.isTransit && l.endTime > now } ?: it.transitLegs.firstOrNull()
        val riding = leg?.let { l -> l.startTime <= now } ?: false
        val departing = legs.firstOrNull { l -> l.isTransit && l.startTime > now }
        val live = s.phase != TripStatus.Phase.CANCELLED && s.phase != TripStatus.Phase.ARRIVED
        val platform = departing?.takeIf { live }?.let { d -> io.github.elija7580.publit.data.TransitLabels.clean(d.from.track) }
        val changed = departing?.from?.let { p -> val a = io.github.elija7580.publit.data.TransitLabels.clean(p.track); val b = io.github.elija7580.publit.data.TransitLabels.clean(p.scheduledTrack); a != null && b != null && a != b } == true
        val then = if (live && riding && departing != null) L10n.f("Then: %@", L10n.f("%@ at %@", departing.lineName, Fmt.time(departing.startTime, departing.from.tz))) else null
        val delay = if (s.delayMinutes > 0) L10n.f("+%lld min", s.delayMinutes) else null

        val title = listOfNotNull(leg?.takeIf { live }?.lineName, s.headline).joinToString(" · ")
        val line2 = listOfNotNull(platform?.let { p -> (if (changed) "⚠ " else "") + L10n.f("Pl. %@", p) }, s.detail.ifEmpty { null }).joinToString(" · ")
        // The stages, done ones ticked: "✓ Walk to Karlsplatz", "▶ U1 → Leopoldau 14:05–14:19", …
        val steps = io.github.elija7580.publit.data.TripGuide.steps(it)
        val current = io.github.elija7580.publit.data.TripGuide.timeIndex(steps, it, now)
        val stages = steps.mapIndexed { i, st ->
            val l = legs[st.legIndex]
            val mark = if (i < current) "✓" else if (i == current) "▶" else "·"
            val what = when (st.kind) {
                io.github.elija7580.publit.data.GuideStep.Kind.WALK -> L10n.f("Walk to %@", legs.drop(st.legIndex + 1).firstOrNull { x -> x.isTransit }?.from?.name ?: trip.toName)
                io.github.elija7580.publit.data.GuideStep.Kind.WAIT -> l.lineName + " · " + l.from.name + (io.github.elija7580.publit.data.TransitLabels.clean(l.from.track)?.let { t -> " · " + L10n.f("Pl. %@", t) } ?: "")
                io.github.elija7580.publit.data.GuideStep.Kind.RIDE -> L10n.f("Get off at %@", l.to.name)
                io.github.elija7580.publit.data.GuideStep.Kind.ARRIVE -> L10n.f("Arrive at %@", trip.toName)
            }
            val time = if (st.kind == io.github.elija7580.publit.data.GuideStep.Kind.ARRIVE) Fmt.time(l.endTime, l.to.tz)
                else if (st.kind == io.github.elija7580.publit.data.GuideStep.Kind.RIDE) Fmt.time(l.endTime, l.to.tz) else Fmt.time(l.startTime, l.from.tz)
            "$mark $time  $what"
        }
        val big = (listOfNotNull(line2.ifEmpty { null }, then) + listOf("") + stages).joinToString("\n")
        val n = builder(ctx, LIVE, title, line2.ifEmpty { trip.toName }, trip.id)
            .setStyle(Notification.BigTextStyle().bigText(big))
            .setSubText(listOfNotNull(trip.toName, delay).joinToString(" · "))
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                if (live && s.nextEvent > now) {
                    setWhen(s.nextEvent.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
                    if (Build.VERSION.SDK_INT >= 24) setChronometerCountDown(true)
                }
                leg?.let { l -> runCatching { setColor(android.graphics.Color.parseColor("#" + l.lineColors.background.removePrefix("#"))) } }
                val total = (it.endTime.epochSecond - it.startTime.epochSecond).coerceAtLeast(60)
                val done = (now.epochSecond - it.startTime.epochSecond).coerceIn(0, total)
                if (Build.VERSION.SDK_INT >= 36) {
                    setStyle(liveProgress(it, total, done))
                    val mins = ((s.nextEvent.epochSecond - now.epochSecond + 59) / 60)
                    if (live && mins in 0..99) setShortCriticalText(L10n.f("%lld min", mins))
                    extras.putBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING, true)
                } else setProgress(total.toInt(), done.toInt(), false)
                val app = ctx.applicationContext as? PublitApplication
                if (app != null && app.passes.forTrip(trip.id, now).isNotEmpty())
                    addAction(Notification.Action.Builder(null, L10n.t("Show ticket"), ticketsIntent(ctx, trip.id)).build())
                addAction(Notification.Action.Builder(null, L10n.t("End trip"),
                    PendingIntent.getBroadcast(ctx, "end".hashCode(), Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_END).putExtra(EXTRA_TRIP, trip.id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build())
            }
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(LIVE_ID, n) }
        // Recomputed at the next moment that matters (and at least every 2 min), from stored times.
        val next = minOf(s.nextEvent.plusSeconds(1), now.plusSeconds(120)).coerceAtLeast(now.plusSeconds(30))
        alarm(ctx, "live", next, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_LIVE))
    }

    /** Android 16 Live Update: one segment per leg (line colour, walks grey), a point at each boarding. */
    @android.annotation.TargetApi(36)
    private fun liveProgress(it: Itinerary, total: Long, done: Long): Notification.Style {
        val scale = 1000.0 / total
        val segments = it.legs.map { l ->
            val len = ((l.endTime.epochSecond - l.startTime.epochSecond) * scale).toInt().coerceAtLeast(1)
            Notification.ProgressStyle.Segment(len).setColor(
                if (l.isTransit) runCatching { android.graphics.Color.parseColor("#" + l.lineColors.background.removePrefix("#")) }.getOrDefault(android.graphics.Color.DKGRAY)
                else android.graphics.Color.LTGRAY)
        }
        val points = it.legs.filter { l -> l.isTransit }.map { l ->
            Notification.ProgressStyle.Point(((l.startTime.epochSecond - it.startTime.epochSecond) * scale).toInt().coerceIn(1, 999))
        }
        return Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressSegments(segments)
            .setProgressPoints(points)
            .setProgress(segments.sumOf { seg -> seg.length }.let { sum -> (done * scale).toInt().coerceIn(0, sum) })
    }

    private fun ticketsIntent(ctx: Context, tripId: String): PendingIntent {
        val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("publit://tickets/$tripId"), ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, ("t" + tripId).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    internal fun endFromNotification(ctx: Context, tripId: String?) {
        val app = ctx.applicationContext as? PublitApplication ?: return
        val t = tripId?.let { id -> app.trips.trip(id) } ?: app.trips.activeTrip ?: run { endLive(ctx); return }
        io.github.elija7580.publit.ui.TripSession.stop(ctx, app.trips, t)
        endLive(ctx)
    }

    fun endLive(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(LIVE_ID)
        val pi = PendingIntent.getBroadcast(ctx, "live".hashCode(), Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_LIVE),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pi?.let { ctx.getSystemService(AlarmManager::class.java).cancel(it) }
    }

    // Plumbing

    internal const val ACTION_POST = "io.github.elija7580.publit.POST"
    internal const val ACTION_LIVE = "io.github.elija7580.publit.LIVE"
    internal const val ACTION_END = "io.github.elija7580.publit.END"

    private fun pending(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("keys", emptySet()).orEmpty()

    private fun setPending(ctx: Context, key: String, on: Boolean) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val keys = p.getStringSet("keys", emptySet()).orEmpty().toMutableSet()
        if (on) keys += key else keys -= key
        p.edit().putStringSet("keys", keys).apply()
    }

    private fun schedule(ctx: Context, key: String, at: Instant, title: String, body: String, tripId: String?) {
        val i = Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_POST)
            .putExtra("key", key).putExtra("title", title).putExtra("body", body).putExtra(EXTRA_TRIP, tripId)
        alarm(ctx, key, at, i)
        setPending(ctx, key, true)
    }

    private fun cancel(ctx: Context, key: String) {
        val pi = PendingIntent.getBroadcast(ctx, key.hashCode(), Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_POST),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        pi?.let { ctx.getSystemService(AlarmManager::class.java).cancel(it) }
        setPending(ctx, key, false)
    }

    private fun alarm(ctx: Context, key: String, at: Instant, intent: Intent) {
        val pi = PendingIntent.getBroadcast(ctx, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pi)
        }
    }

    internal fun builder(ctx: Context, channel: String, title: String, body: String, tripId: String?): Notification.Builder {
        val open = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_TRIP, tripId)
        val tap = PendingIntent.getActivity(ctx, (tripId ?: "app").hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, channel) else @Suppress("DEPRECATION") Notification.Builder(ctx)
        return b.setSmallIcon(R.drawable.ic_launcher_monochrome).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(tap).setAutoCancel(channel != LIVE)
    }

    internal fun post(ctx: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        setPending(ctx, key, false)
        if (!allowed(ctx)) return
        val n = builder(ctx, REMINDERS, intent.getStringExtra("title").orEmpty(), intent.getStringExtra("body").orEmpty(),
            intent.getStringExtra(EXTRA_TRIP)).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(key.hashCode(), n) }
    }

    internal fun liveTick(ctx: Context) {
        val app = ctx.applicationContext as? PublitApplication ?: return
        val trip = app.trips.current() ?: run { endLive(ctx); return }
        if (trip.followLive == true || app.trips.activeTrip?.id == trip.id) showLive(ctx, trip) else endLive(ctx)
    }
}

/** After a restart (or an app update) alarms are gone: set them again from the saved trips. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val app = context.applicationContext as? PublitApplication ?: return
            val now = Instant.now()
            for (t in app.trips.upcoming(now)) {
                if (t.followLive == true && t.startTime.isAfter(now.plusSeconds(30))) TripNotifications.scheduleLeave(context, t)
            }
            app.trips.activeTrip?.let { TripNotifications.scheduleGetOff(context, it.itinerary, it.id) }
            TripNotifications.liveTick(context)
            LineAlerts.schedule(context)
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            when (intent.action) {
                TripNotifications.ACTION_POST -> TripNotifications.post(context, intent)
                TripNotifications.ACTION_LIVE -> TripNotifications.liveTick(context)
                TripNotifications.ACTION_END -> TripNotifications.endFromNotification(context, intent.getStringExtra(TripNotifications.EXTRA_TRIP))
            }
        }
    }
}
