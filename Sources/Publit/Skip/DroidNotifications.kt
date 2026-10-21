// Publit for Android: local notifications (leave/get-off reminders, trip changes, line alerts),
// the Android counterpart of UNUserNotificationCenter (see Android/UserNotificationsShim.swift).
package publit.module

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object DroidNotify {
    const val PERMISSION_REQUEST = 7581
    const val CHANNEL_REMINDERS = "reminders"
    const val CHANNEL_ALERTS = "alerts"
    const val CHANNEL_LIVE = "live"
    private const val PREFS = "publit.notifications"
    var pendingPermissionRequest: String? = null

    fun channels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDERS, res(ctx, "channel_reminders", "Reminders"), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, res(ctx, "channel_alerts", "Trip and line alerts"), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, res(ctx, "channel_live", "Live trip"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
    }

    /** App strings and icons live in the app module (Android/app/src/main/res). */
    fun res(ctx: Context, name: String, fallback: String): String {
        val id = ctx.resources.getIdentifier(name, "string", ctx.packageName)
        return if (id != 0) ctx.getString(id) else fallback
    }

    fun icon(ctx: Context): Int {
        val id = ctx.resources.getIdentifier("ic_notification", "drawable", ctx.packageName)
        return if (id != 0) id else android.R.drawable.ic_dialog_info
    }

    fun allowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    fun openIntent(ctx: Context, url: String, code: Int): PendingIntent {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent()
        intent.action = Intent.ACTION_VIEW
        if (url.isNotEmpty()) intent.data = Uri.parse(url)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun post(ctx: Context, id: String, title: String, body: String, url: String, urgent: Boolean, sound: Boolean) {
        channels(ctx)
        if (!allowed(ctx)) return
        val n = NotificationCompat.Builder(ctx, if (urgent) CHANNEL_REMINDERS else CHANNEL_ALERTS)
            .setSmallIcon(icon(ctx))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (urgent) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(openIntent(ctx, url, id.hashCode()))
        if (!sound) n.setSilent(true)
        try { NotificationManagerCompat.from(ctx).notify(id, 0, n.build()) } catch (e: SecurityException) {}
    }

    fun schedule(ctx: Context, id: String, json: JSONObject) {
        val fire = json.getDouble("fire").toLong()
        if (fire <= System.currentTimeMillis() + 1000) {
            post(ctx, id, json.getString("title"), json.getString("body"), json.getString("url"), json.getBoolean("urgent"), json.getBoolean("sound"))
            remove(ctx, id)
            return
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(id, json.toString()).apply()
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(ctx, id)
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fire, pi)
        } else {
            am.setWindow(AlarmManager.RTC_WAKEUP, fire - 30_000, 60_000, pi)
        }
    }

    fun alarmIntent(ctx: Context, id: String): PendingIntent {
        val intent = Intent(ctx, ReminderReceiver::class.java).setAction("publit.REMINDER").putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun remove(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(id).apply()
    }

    fun cancel(ctx: Context, id: String) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx, id))
        remove(ctx, id)
    }

    fun pending(ctx: Context): Map<String, *> = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all

    /** After a reboot alarms are gone: schedule them again. */
    fun rescheduleAll(ctx: Context) {
        for ((id, v) in pending(ctx)) {
            try { schedule(ctx, id, JSONObject(v as String)) } catch (e: Exception) { remove(ctx, id) }
        }
    }
}

/** Fires the scheduled notification (and reschedules after boot). */
/** The ongoing "live trip" notification (Android counterpart of the Live Activity). */
object DroidLive {
    fun show(ctx: Context, json: JSONObject) {
        DroidNotify.channels(ctx)
        if (!DroidNotify.allowed(ctx)) return
        val id = json.getString("id")
        val next = json.optDouble("next", 0.0).toLong()
        val timeout = json.optDouble("timeout", 0.0).toLong()
        val parts = mutableListOf<String>()
        json.optString("line").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        json.optString("platform").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        json.optString("detail").takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        val body = parts.joinToString(" · ")
        val big = listOfNotNull(body.ifEmpty { null }, json.optString("then").ifEmpty { null }).joinToString("\n")
        val n = NotificationCompat.Builder(ctx, DroidNotify.CHANNEL_LIVE)
            .setSmallIcon(DroidNotify.icon(ctx))
            .setContentTitle(json.optString("headline"))
            .setContentText(body)
            .setSubText(json.optString("title"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(big))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(DroidNotify.openIntent(ctx, json.optString("url"), id.hashCode()))
        if (next > System.currentTimeMillis()) {
            n.setWhen(next).setShowWhen(true).setUsesChronometer(true)
            if (Build.VERSION.SDK_INT >= 24) n.setChronometerCountDown(true)
        }
        json.optString("lineColor").takeIf { it.isNotEmpty() }?.let {
            try { n.setColor(android.graphics.Color.parseColor(if (it.startsWith("#")) it else "#$it")) } catch (e: Exception) {}
        }
        json.optString("ticketsURL").takeIf { it.isNotEmpty() }?.let {
            n.addAction(0, json.optString("ticketsLabel", "Ticket"), DroidNotify.openIntent(ctx, it, id.hashCode() + 1))
        }
        if (timeout > 0) { n.setTimeoutAfter(timeout); n.setOngoing(false) } else n.setOngoing(true)
        // Android 16 Live Updates: promotion is a request; the OS/user decides whether to show it.
        var notification = n.build()
        if (Build.VERSION.SDK_INT >= 36 && timeout <= 0) {
            notification = android.app.Notification.Builder.recoverBuilder(ctx, notification)
                .setRequestPromotedOngoing(true)
                .setShortCriticalText(json.optString("line").take(7))
                .build()
        }
        try { NotificationManagerCompat.from(ctx).notify("live-$id", 1, notification) } catch (e: SecurityException) {}
    }

    fun end(ctx: Context, id: String) = NotificationManagerCompat.from(ctx).cancel("live-$id", 1)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == "android.intent.action.MY_PACKAGE_REPLACED") {
            DroidNotify.rescheduleAll(context)
            return
        }
        val id = intent.getStringExtra("id") ?: return
        val raw = context.getSharedPreferences("publit.notifications", Context.MODE_PRIVATE).getString(id, null) ?: return
        val json = JSONObject(raw)
        DroidNotify.post(context, id, json.getString("title"), json.getString("body"), json.getString("url"), json.getBoolean("urgent"), json.getBoolean("sound"))
        DroidNotify.remove(context, id)
    }
}
