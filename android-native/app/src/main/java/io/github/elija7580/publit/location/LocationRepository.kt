package io.github.elija7580.publit.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class Fix(val coordinate: LatLon, val accuracy: Float, val elapsedNanos: Long, val source: String)

enum class LocationAccess { NOT_ASKED, DENIED, APPROXIMATE, PRECISE }

/**
 * Where the phone is. Google's fused provider where Play services work (Play build), and always the platform
 * GPS and network providers next to it: on some phones (Huawei, older Android) one of them stays
 * silent, and Nearby must not wait for it. Updates run only while someone collects `fixes`.
 */
class LocationRepository(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun access(): LocationAccess {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
        return when {
            fine -> LocationAccess.PRECISE
            coarse -> LocationAccess.APPROXIMATE
            else -> LocationAccess.NOT_ASKED
        }
    }

    /** The system-wide location switch (Quick Settings). */
    fun servicesOn(): Boolean = runCatching { LocationManagerCompat.isLocationEnabled(manager) }.getOrDefault(true)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    val fixes: Flow<Fix> = callbackFlow {
        val access = access()
        if (access == LocationAccess.NOT_ASKED || access == LocationAccess.DENIED) { close(); return@callbackFlow }
        val precise = access == LocationAccess.PRECISE
        var best: Fix? = null

        fun offer(location: Location?, source: String) {
            location ?: return
            if (!location.latitude.isFinite() || !location.longitude.isFinite()) return
            val fix = Fix(LatLon(location.latitude, location.longitude),
                if (location.hasAccuracy()) location.accuracy else 1000f, location.elapsedRealtimeNanos, source)
            if (!better(fix, best)) return
            best = fix
            trySend(fix)
        }

        // Last known positions first: departures can load before GPS answers.
        val providers = buildList {
            if (precise && manager.allProviders.contains(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (manager.allProviders.contains(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
            if (manager.allProviders.contains(LocationManager.PASSIVE_PROVIDER)) add(LocationManager.PASSIVE_PROVIDER)
        }
        for (p in providers) runCatching { offer(manager.getLastKnownLocation(p), "last-$p") }

        // Platform providers. Every callback is implemented: before Android 11 they have no default bodies.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = offer(location, location.provider ?: "platform")
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Required before Android 10")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        for (p in providers.filter { it != LocationManager.PASSIVE_PROVIDER }) {
            runCatching { manager.requestLocationUpdates(p, 2_000L, 5f, listener, Looper.getMainLooper()) }
        }

        // Google's fused provider, when Play services are there and working (Play build only, see FusedLocation).
        val stopFused = FusedLocation.start(context, precise) { location, source -> offer(location, source) }

        awaitClose {
            runCatching { manager.removeUpdates(listener) }
            stopFused?.invoke()
        }
    }

    companion object {
        /**
         * Newer wins, except that a coarse fix does not replace a clearly better one from the last
         * 15 seconds (network after GPS would make the position jump back and forth).
         */
        fun better(fix: Fix, current: Fix?): Boolean {
            current ?: return true
            if (fix.elapsedNanos < current.elapsedNanos) return false
            val ageOfCurrent = (fix.elapsedNanos - current.elapsedNanos) / 1_000_000_000.0
            if (fix.accuracy > current.accuracy * 2 && ageOfCurrent < 15) return false
            return true
        }

        fun ageSeconds(fix: Fix): Double = (SystemClock.elapsedRealtimeNanos() - fix.elapsedNanos) / 1e9
    }
}
