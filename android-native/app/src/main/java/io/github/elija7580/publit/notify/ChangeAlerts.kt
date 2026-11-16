package io.github.elija7580.publit.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import io.github.elija7580.publit.MainActivity
import io.github.elija7580.publit.PublitApplication
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.ChangeWatch
import io.github.elija7580.publit.data.DelayHistory
import io.github.elija7580.publit.data.DelayHistoryStore
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.SmartRouting
import io.github.elija7580.publit.data.TripLearning
import java.time.Instant

/**
 * "Your connection at … is unlikely" (port of iOS `ChangeRiskNotifier`): when a change of a started or
 * followed trip drops below "likely" or its connection is cancelled. Tapping opens routes from the change
 * stop (still on the feeder) or from here, to the destination, leaving now. On the device only.
 */
object ChangeRiskNotifier {
    fun follow(ctx: Context, trip: SavedTrip, here: LatLon? = null, speed: Double? = null, now: Instant = Instant.now()) {
        if (!SmartRouting.isEnabled || !ChangeWatch.isEnabled) return
        var changes = SmartRouting.changes(trip.itinerary, now)
        if (here != null) {
            val pace = speed ?: SmartRouting.prefs?.getFloat(SmartRouting.MEASURED_SPEED, 0f)?.toDouble()?.takeIf { it > 0.3 } ?: 1.3
            changes = changes.map { c ->
                val table = DelayHistoryStore.table(DelayHistory.country(c.outgoing.routeId))
                ChangeWatch.onFoot(c, here, now, pace, table)?.let { p -> c.copy(chance = minOf(c.chance, p)) } ?: c
            }
        }
        for (c in ChangeWatch.due(changes, ChangeWatch.warned(), now)) {
            ChangeWatch.markWarned(c)
            post(ctx, c, trip, here, now)
        }
    }

    private fun post(ctx: Context, c: SmartRouting.Change, trip: SavedTrip, here: LatLon?, now: Instant) {
        if (!TripNotifications.allowed(ctx)) return
        val end = trip.itinerary.legs.lastOrNull()?.to ?: return
        val link = Uri.Builder().scheme("publit").authority("alternatives")
            .appendQueryParameter("tn", trip.toName).appendQueryParameter("tlat", end.lat.toString()).appendQueryParameter("tlon", end.lon.toString())
        val fromHere = here != null && now >= c.incoming.endTime.minusSeconds(60)
        if (!fromHere) {
            val s = c.incoming.to
            link.appendQueryParameter("fn", s.name).appendQueryParameter("flat", s.lat.toString()).appendQueryParameter("flon", s.lon.toString())
        }
        val open = Intent(Intent.ACTION_VIEW, link.build(), ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val key = ChangeWatch.key(c)
        val tap = PendingIntent.getActivity(ctx, key.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = TripNotifications.builder(ctx, "reminders", L10n.t("Change at risk"), ChangeWatch.message(c), trip.id).setContentIntent(tap).build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(("changerisk|$key").hashCode(), n) }
    }
}

/** Location fixes of a started trip → learning (no track kept) and change alerts on foot. */
object TripLearningSession {
    private val recorders = HashMap<String, TripLearning.Recorder>()
    private val lastFollow = HashMap<String, Long>()
    private val lastRefresh = HashMap<String, Long>()

    /** From 2 min before the first ride until 10 min after the last one, when learning is on. */
    fun wantsTracking(trip: SavedTrip?, now: Instant = Instant.now()): Boolean {
        val legs = trip?.itinerary?.transitLegs ?: return false
        if (!TripLearning.isEnabled || !SmartRouting.isEnabled || legs.size < 2) return false
        return legs.first().startTime.minusSeconds(120) <= now && legs.last().endTime.plusSeconds(600) >= now
    }

    @Synchronized fun handle(ctx: Context, fix: Location, trip: SavedTrip, now: Instant = Instant.now()) {
        val here = LatLon(fix.latitude, fix.longitude)
        val app = ctx.applicationContext as? PublitApplication
        if (app != null && now.epochSecond - (lastRefresh[trip.id] ?: 0) > 120) {
            lastRefresh[trip.id] = now.epochSecond
            Thread { runCatching { kotlinx.coroutines.runBlocking { io.github.elija7580.publit.ui.TripSession.refresh(ctx, app.client, app.trips, trip) } } }.start()
        }
        val current = app?.trips?.trip(trip.id) ?: trip
        if (TripLearning.isEnabled) {
            val rec = recorders.getOrPut(trip.id) { TripLearning.Recorder() }
            TripLearning.add(rec.observe(here, if (fix.hasAccuracy()) fix.accuracy.toDouble() else -1.0, now, current.itinerary))
        }
        if (now.epochSecond - (lastFollow[trip.id] ?: 0) > 30) {
            lastFollow[trip.id] = now.epochSecond
            val speed = if (fix.hasSpeed() && fix.speed >= 0.3f) fix.speed.toDouble() else null
            ChangeRiskNotifier.follow(ctx, current, here, speed, now)
        }
    }
}
