package io.github.elija7580.publit.notify

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import io.github.elija7580.publit.PublitApplication
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.GetOffAlert
import io.github.elija7580.publit.data.GetOffWatch
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.SavedTrip
import io.github.elija7580.publit.data.TripGuide
import io.github.elija7580.publit.data.TripProgress
import java.time.Instant

/**
 * The get-off alert from where the phone is on board (GetOffAlerts on iOS #69): while a started trip rides a
 * vehicle, each fix is laid onto the line; at the chosen milestone ("one stop before" …) a notification goes
 * out at once and replaces the time-based reminder of that ride. One alert per ride, whichever comes first.
 * Everything happens on the phone; nothing about the position leaves it.
 */
object GetOffAlerts {
    /** Fixes less accurate than this are ignored (inside a station, in a tunnel). */
    const val MAX_ACCURACY = 100f
    private const val PREFS = "publit.reminders"
    private const val DELIVERED = "getOffDelivered"

    fun alert(ctx: Context) = GetOffAlert.current(ctx.getSharedPreferences("publit", Context.MODE_PRIVATE))

    /** The ride on now (or about to board, or just left): the one the alert is for. */
    fun rideNow(trip: SavedTrip, now: Instant, before: Long = 120): Leg? =
        trip.itinerary.transitLegs.firstOrNull { it.startTime.minusSeconds(before) <= now && it.endTime.plusSeconds(600) >= now }

    /**
     * Location is needed while a started trip still has a ride ahead, and only when the alert uses location.
     * (iOS switches it on per ride; Android may not start location in the background, so it runs from the start.)
     */
    fun wantsTracking(trip: SavedTrip?, alert: GetOffAlert, now: Instant = Instant.now()): Boolean =
        trip != null && alert.usesLocation && trip.itinerary.transitLegs.any { it.endTime.plusSeconds(600) >= now }

    fun wasDelivered(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DELIVERED, "").orEmpty().split('\n').contains(key)

    fun markDelivered(ctx: Context, key: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val list = p.getString(DELIVERED, "").orEmpty().split('\n').filter { it.isNotEmpty() } + key
        p.edit().putString(DELIVERED, list.takeLast(50).joinToString("\n")).apply()
    }

    /** "Next stop: Odeonsplatz · 2 stops to go", per running trip, for the live notification. */
    private val notes = HashMap<String, String>()
    fun rideNote(tripId: String): String? = synchronized(notes) { notes[tripId] }
    fun forget(tripId: String) = synchronized(notes) { notes.remove(tripId) }

    fun handle(ctx: Context, fix: Location, trip: SavedTrip, now: Instant = Instant.now()) {
        if (!fix.hasAccuracy() || fix.accuracy > MAX_ACCURACY || now.toEpochMilli() - fix.time > 30_000) return
        val here = LatLon(fix.latitude, fix.longitude)
        val leg = rideNow(trip, now, 60) ?: return
        val ride = TripGuide.ridePosition(leg, now, here)
        if (!ride.fromGps) return
        val stops = leg.allStops
        TripProgress.nextStopIndex(ride.progress, stops.size)?.let { next ->
            val left = TripGuide.stopsLeft(ride.progress, stops.size)
            val note = if (left <= 1) L10n.f("Next stop: %@. Get off there.", stops[next].name)
                else L10n.f("Next stop: %@ · %lld stops to go", stops[next].name, left)
            val changed = synchronized(notes) { (notes.put(trip.id, note) != note) }
            if (changed) TripNotifications.showLive(ctx, trip, now)
        }
        val key = TripNotifications.getOffKey(leg)
        if (wasDelivered(ctx, key)) return
        if (!GetOffWatch.isDue(alert(ctx), ride.progress, stops.size, here.distanceTo(leg.to.coordinate))) return
        markDelivered(ctx, key)
        TripNotifications.postGetOffNow(ctx, leg, GetOffWatch.message(leg.to.name, TripGuide.stopsLeft(ride.progress, stops.size)), trip.id)
    }
}

/**
 * Keeps location coming during a started trip when the get-off alert uses it, also with the screen locked
 * (the blue indicator of iOS is the live notification here, which this service holds in the foreground).
 * Started from the app (start trip, live trip screen); stops by itself after the last ride.
 */
class GetOffService : Service(), LocationListener {
    private var listening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Started with startForegroundService: always go to the foreground first, then decide.
        val trip = activeTrip()
        if (trip != null) TripNotifications.showLive(this, trip)
        val n = TripNotifications.lastLive ?: TripNotifications.builder(this, "live", L10n.t("Get-off alert"), trip?.toName ?: "Publit", trip?.id).setOngoing(true).build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(TripNotifications.LIVE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(TripNotifications.LIVE_ID, n)
        }.isSuccess
        if (!ok || !GetOffAlerts.wantsTracking(trip, GetOffAlerts.alert(this)) || !hasLocation(this)) { stopSelf(); return START_NOT_STICKY }
        if (!listening) runCatching {
            val lm = getSystemService(LocationManager::class.java)
            // Every 25 m or 5 s: enough to tell the stops apart, light on older phones.
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, 5_000L, 25f, this, Looper.getMainLooper())
            }
            listening = true
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        val trip = activeTrip()
        if (!GetOffAlerts.wantsTracking(trip, GetOffAlerts.alert(this))) { stopSelf(); return }
        runCatching { GetOffAlerts.handle(this, location, trip!!) }
    }

    @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        runCatching { getSystemService(LocationManager::class.java).removeUpdates(this) }
        listening = false
        // The live notification stays while the trip goes on (on foot); without a trip it goes.
        val keep = activeTrip() != null
        runCatching { stopForeground(if (keep) STOP_FOREGROUND_DETACH else STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun activeTrip(): SavedTrip? = (applicationContext as? PublitApplication)?.trips?.activeTrip

    companion object {
        fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Starts or stops tracking to match the active trip and the setting. Call while the app is in front. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext as? PublitApplication ?: return
            val want = GetOffAlerts.wantsTracking(app.trips.activeTrip, GetOffAlerts.alert(ctx)) && hasLocation(ctx)
            val i = Intent(ctx, GetOffService::class.java)
            runCatching { if (want) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } else ctx.stopService(i) }
        }

        fun stop(ctx: Context) { runCatching { ctx.stopService(Intent(ctx, GetOffService::class.java)) } }
    }
}
