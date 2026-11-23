// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/** Google's fused provider, when Play services are there and working (Play build only). */
object FusedLocation {
    private fun playServices(context: Context): Boolean = runCatching {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    }.getOrDefault(false)

    /** Starts updates and returns how to stop them, or null when there is no fused provider. */
    @SuppressLint("MissingPermission")
    fun start(context: Context, precise: Boolean, offer: (Location?, String) -> Unit): (() -> Unit)? {
        if (!playServices(context)) return null
        val fused = runCatching { LocationServices.getFusedLocationProviderClient(context) }.getOrNull() ?: return null
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) { result.locations.forEach { offer(it, "fused") } }
        }
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        runCatching {
            fused.lastLocation.addOnSuccessListener { offer(it, "fused-last") }
            fused.requestLocationUpdates(
                LocationRequest.Builder(priority, 4_000L).setMinUpdateIntervalMillis(1_000L).setMinUpdateDistanceMeters(5f).build(),
                callback, Looper.getMainLooper())
        }
        return { runCatching { fused.removeLocationUpdates(callback) } }
    }
}
