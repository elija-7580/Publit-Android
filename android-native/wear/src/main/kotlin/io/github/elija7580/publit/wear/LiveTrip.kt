// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.wear

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** The trip under way on the phone, as the phone sends it (TripNotifications.showLive). */
data class LiveTrip(
    val id: String,
    val phase: String,
    val cue: String,
    val headline: String,
    val detail: String,
    val next: Long,
    val start: Long,
    val end: Long,
    val destination: String,
    val delay: Int,
    val stepMode: String,
    val line: String?,
    val lineColor: String?,
    val lineText: String?,
    val direction: String?,
    val legTime: Long?,
    val boardAt: String?,
    val platform: String?,
    val platformChanged: Boolean,
    val then: String?,
    val stages: List<Stage>,
    /** Shape of every leg for the map (may be empty), and the index of the leg under way. */
    val legs: List<Shape> = emptyList(),
    val current: Int = 0,
) {
    data class Stage(val text: String, val done: Boolean, val now: Boolean)
    data class Shape(val walk: Boolean, val color: String, val points: List<Pair<Double, Double>>)

    val riding get() = phase == "RIDING"

    companion object {
        fun parse(json: String): LiveTrip? = runCatching {
            val o = JSONObject(json)
            val stages = o.optJSONArray("stages")
            LiveTrip(
                id = o.getString("id"), phase = o.optString("phase"), cue = o.optString("cue"),
                headline = o.optString("headline"), detail = o.optString("detail"),
                next = o.optLong("next"), start = o.optLong("start"), end = o.optLong("end"),
                destination = o.optString("destination"), delay = o.optInt("delay"), stepMode = o.optString("stepMode", "WALK"),
                line = o.optString("line").ifEmpty { null }, lineColor = o.optString("lineColor").ifEmpty { null },
                lineText = o.optString("lineText").ifEmpty { null }, direction = o.optString("direction").ifEmpty { null },
                legTime = if (o.has("legTime")) o.getLong("legTime") else null, boardAt = o.optString("boardAt").ifEmpty { null },
                platform = o.optString("platform").ifEmpty { null }, platformChanged = o.optBoolean("platformChanged"),
                then = o.optString("then").ifEmpty { null },
                stages = (0 until (stages?.length() ?: 0)).map { i ->
                    val s = stages!!.getJSONObject(i)
                    Stage(s.optString("text"), s.optBoolean("done"), s.optBoolean("now"))
                },
                legs = o.optJSONArray("legs")?.let { a ->
                    (0 until a.length()).map { i ->
                        val l = a.getJSONObject(i)
                        val pts = l.optJSONArray("pts")
                        Shape(l.optBoolean("walk"), l.optString("color", "#888888"),
                            (0 until (pts?.length() ?: 0)).map { j -> val p = pts!!.getJSONArray(j); p.getDouble(0) to p.getDouble(1) })
                    }
                }.orEmpty(),
                current = o.optInt("current"),
            )
        }.getOrNull()
    }
}

/**
 * Keeps the trip under way on the watch and shows it as an ongoing activity (on the watch face and in recents, like a
 * route in Maps): what to do now and a countdown. A tap opens the live trip screen. Nothing runs in between: the
 * countdown is drawn by the system, and the phone sends a new state only when something visible changed.
 */
object LiveTripStore {
    private const val PREFS = "publit.live"
    private const val KEY = "trip"
    private const val CHANNEL = "live"
    const val NOTIFICATION_ID = 7001

    private val state = MutableStateFlow<LiveTrip?>(null)
    private var loaded = false

    /** The trip, as long as it is not more than ten minutes past its arrival. */
    fun trip(ctx: Context): StateFlow<LiveTrip?> {
        if (!loaded) {
            loaded = true
            state.value = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)?.let(LiveTrip::parse)
        }
        state.value?.let { if (System.currentTimeMillis() > it.end + 10 * 60_000) clear(ctx) }
        return state
    }

    /** Returns true for a trip that was not shown before (just started on the phone). */
    fun update(ctx: Context, json: String): Boolean {
        val trip = LiveTrip.parse(json) ?: return false
        val isNew = trip(ctx).value?.id != trip.id
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json).apply()
        state.value = trip
        loaded = true
        show(ctx, trip)
        return isNew
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        state.value = null
        loaded = true
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    fun cueLabel(ctx: Context, cue: String): String = when (cue) {
        "leave" -> ctx.getString(R.string.leave_in)
        "departs" -> ctx.getString(R.string.departs_in)
        "getoff" -> ctx.getString(R.string.get_off_in)
        "arrive" -> ctx.getString(R.string.arrive_in)
        else -> ""
    }

    fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, LiveTripActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun show(ctx: Context, trip: LiveTrip) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.live_trip), NotificationManager.IMPORTANCE_LOW))
        val now = System.currentTimeMillis()
        val cue = cueLabel(ctx, trip.cue)
        val open = openIntent(ctx)
        val builder = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(listOfNotNull(trip.line, trip.headline).joinToString(" · "))
            .setContentText(listOfNotNull(trip.platform, trip.direction?.let { "→ $it" }).joinToString(" · ").ifEmpty { trip.destination })
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
        if (trip.next > now) builder.setWhen(trip.next).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        val status = if (trip.next > now && cue.isNotEmpty()) {
            Status.Builder()
                .addTemplate("#cue# #timer#")
                .addPart("cue", Status.TextPart(cue))
                .addPart("timer", Status.TimerPart(SystemClock.elapsedRealtime() + (trip.next - now)))
                .build()
        } else {
            Status.Builder().addTemplate("#text#").addPart("text", Status.TextPart(trip.headline)).build()
        }
        OngoingActivity.Builder(ctx, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_notification)
            .setTouchIntent(open)
            .setStatus(status)
            .build()
            .apply(ctx)
        runCatching { nm.notify(NOTIFICATION_ID, builder.build()) }
    }
}

/** Receives the trip under way from the phone (Data Layer item `/live-trip`). */
class LiveTripService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.dataItem.uri.path != "/live-trip") continue
            when (event.type) {
                DataEvent.TYPE_CHANGED -> {
                    val json = event.dataItem.data?.toString(Charsets.UTF_8) ?: continue
                    // A trip just started on the phone opens on the watch, like a route in Maps. Where the system does
                    // not let an app open from the background, the ongoing activity on the watch face is the way in.
                    if (LiveTripStore.update(this, json)) runCatching { LiveTripStore.openIntent(this).send() }
                }
                DataEvent.TYPE_DELETED -> LiveTripStore.clear(this)
            }
        }
    }
}
