package io.github.elija7580.publit.notify

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.elija7580.publit.PublitApplication
import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.data.SavedLine
import io.github.elija7580.publit.data.StopTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/** Service changes on a saved line worth a notification: alerts and cancelled departures (LineDisruption on iOS). */
data class LineDisruption(val fingerprint: String, val title: String, val body: String) {
    companion object {
        fun detect(line: SavedLine, stopTimes: List<StopTime>, now: Instant = Instant.now(), horizon: Long = 2 * 3600): List<LineDisruption> {
            val mine = stopTimes.filter { line.matches(it) && it.departureTime >= now.minusSeconds(60) && it.departureTime <= now.plusSeconds(horizon) }
            val out = ArrayList<LineDisruption>()
            val seen = HashSet<String>()
            for (st in mine) for (a in st.serviceAlerts) if (seen.add(a.headerText)) {
                val body = if (a.descriptionText.isEmpty()) L10n.f("At %@", line.stopName) else a.descriptionText.take(240)
                out += LineDisruption("alert|${line.id}|${stableHash(a.headerText)}", "${line.lineName}: ${a.headerText}", body)
            }
            val cancelled = mine.filter { it.isCancelled }
            cancelled.firstOrNull()?.let { first ->
                val times = cancelled.take(3).map { Fmt.time(it.scheduledDepartureTime ?: it.departureTime, first.place.tz) }
                val title = if (cancelled.size == 1) L10n.f("%@: departure cancelled", line.lineName) else L10n.f("%@: %lld departures cancelled", line.lineName, cancelled.size)
                out += LineDisruption("cancel|${line.id}|" + cancelled.map { it.tripId }.sorted().joinToString(","), title,
                    L10n.f("At %@: %@", line.stopName, times.joinToString(", ")))
            }
            return out
        }

        /** FNV-1a: the same text gives the same value in every launch. */
        fun stableHash(s: String): String {
            var h = -0x340d631b7bdddcdbL
            for (b in s.toByteArray()) { h = h xor (b.toLong() and 0xff); h *= 0x100000001b3L }
            return java.lang.Long.toUnsignedString(h, 16)
        }
    }
}

/**
 * Notifications about alerts and cancellations on saved lines (LineAlertMonitor on iOS): checked when the app
 * opens and about every 30 minutes by an inexact alarm (no background service).
 */
object LineAlerts {
    const val ENABLED = "lineAlerts"
    private const val LAST = "lineAlerts.lastCheck"
    private const val LEDGER = "publit.disruptions.v1"
    private const val MAX_STOPS = 8

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(ENABLED, false)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("publit", Context.MODE_PRIVATE)

    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(ENABLED, on).apply(); schedule(ctx) }

    /** About every 30 minutes while there is something to watch; off otherwise. */
    fun schedule(ctx: Context) {
        val app = ctx.applicationContext as? PublitApplication ?: return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, 7300, Intent(ctx, LineAlertReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (enabled(ctx) && app.favorites.state.value.lines.isNotEmpty())
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR, AlarmManager.INTERVAL_HALF_HOUR, pi)
        else am.cancel(pi)
    }

    /** Checks saved lines and posts one notification per new disruption. */
    suspend fun check(ctx: Context, minIntervalSeconds: Long = 15 * 60, now: Instant = Instant.now()): Int {
        val p = prefs(ctx)
        if (!enabled(ctx) || !TripNotifications.allowed(ctx)) return 0
        if (now.epochSecond - p.getLong(LAST, 0) < minIntervalSeconds) return 0
        p.edit().putLong(LAST, now.epochSecond).apply()
        val app = ctx.applicationContext as? PublitApplication ?: return 0
        val lines = app.favorites.state.value.lines
        if (lines.isEmpty()) return 0
        val found = ArrayList<Pair<LineDisruption, SavedLine>>()
        for ((stopId, saved) in lines.groupBy { it.stopId }.entries.take(MAX_STOPS)) {
            val res = runCatching { app.client.departures(stopId, 60, 150) }.getOrNull() ?: continue
            for (line in saved) LineDisruption.detect(line, res.stopTimes, now).forEach { found += it to line }
        }
        // Each disruption is announced once; the ledger forgets after two days.
        val ledger = runCatching { JSONObject(p.getString(LEDGER, "{}")!!) }.getOrDefault(JSONObject())
        ledger.keys().asSequence().toList().forEach { k -> if (now.epochSecond - ledger.optLong(k) > 2 * 24 * 3600) ledger.remove(k) }
        val fresh = found.filter { !ledger.has(it.first.fingerprint) }
        fresh.forEach { ledger.put(it.first.fingerprint, now.epochSecond) }
        p.edit().putString(LEDGER, ledger.toString()).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for ((d, line) in fresh.take(5)) {
            val open = Intent(ctx, io.github.elija7580.publit.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("stopId", line.stopId).putExtra("stopName", line.stopName)
            val tap = PendingIntent.getActivity(ctx, d.fingerprint.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "reminders") else @Suppress("DEPRECATION") Notification.Builder(ctx)
            val n = b.setSmallIcon(io.github.elija7580.publit.R.drawable.ic_launcher_monochrome).setContentTitle(d.title).setContentText(d.body)
                .setStyle(Notification.BigTextStyle().bigText(d.body)).setContentIntent(tap).setAutoCancel(true).setGroup("lineAlerts").build()
            runCatching { nm.notify(d.fingerprint.hashCode(), n) }
        }
        return fresh.size
    }
}

class LineAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { LineAlerts.check(context) } finally { done.finish() }
        }
    }
}
