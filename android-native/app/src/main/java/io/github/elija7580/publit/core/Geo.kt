package io.github.elija7580.publit.core

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Plain coordinate, as LatLon in the iOS app. */
data class LatLon(val lat: Double, val lon: Double) {
    val isValid: Boolean get() = lat.isFinite() && lon.isFinite() && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180

    /** `lat,lon` for the MOTIS API, to about 1 m (full GPS precision only defeats caching). */
    val apiValue: String get() = format(lat, 5) + "," + format(lon, 5)

    fun distanceTo(other: LatLon): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat)
        val p2 = Math.toRadians(other.lat)
        val dp = Math.toRadians(other.lat - lat)
        val dl = Math.toRadians(other.lon - lon)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val b = max(0.0, min(1.0, a))
        return 2 * r * atan2(sqrt(b), sqrt(1 - b))
    }

    companion object {
        /** Locale-independent fixed decimals without trailing zeros ("48.13715", "11.5"). */
        fun format(value: Double, decimals: Int): String {
            var s = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
            if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
            return if (s == "-0") "0" else s
        }

        fun fromApi(value: String): LatLon? {
            val parts = value.split(',').mapNotNull { it.trim().toDoubleOrNull() }
            if (parts.size != 2) return null
            return LatLon(parts[0], parts[1]).takeIf { it.isValid }
        }
    }
}
