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

    fun showLive(ctx: Context, trip: SavedTrip, now: Instant = Instant.now()) {
        if (!allowed(ctx)) return
        val s = TripStatus.at(now, trip.itinerary)
        if (s.phase == TripStatus.Phase.ARRIVED && now > trip.endTime.plusSeconds(300)) { endLive(ctx); return }
        val delay = if (s.delayMinutes > 0) " · " + L10n.f("+%lld min", s.delayMinutes) else ""
        val n = builder(ctx, LIVE, s.headline + delay, s.detail, trip.id)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_NAVIGATION)
            .setWhen(s.nextEvent.toEpochMilli()).setShowWhen(true).setUsesChronometer(true)
            .apply { if (Build.VERSION.SDK_INT >= 24) setChronometerCountDown(true) }
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(LIVE_ID, n) }
        // Recomputed at the next moment that matters (and at least every 5 min), from stored times.
        val next = minOf(s.nextEvent.plusSeconds(1), now.plusSeconds(300)).coerceAtLeast(now.plusSeconds(30))
        alarm(ctx, "live", next, Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_LIVE))
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
            }
        }
    }
}
