package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import java.time.Instant

/** Where a vehicle is along its trip, estimated from the (real-time) stop times. */
sealed class TripProgress {
    object NotStarted : TripProgress()
    data class AtStop(val index: Int) : TripProgress()
    /** Between stop `index` and `index + 1`; `fraction` 0…1 by time. */
    data class Between(val index: Int, val fraction: Double) : TripProgress()
    object Finished : TripProgress()

    /** Stops passed, as a number (2.5 = halfway between the third and fourth stop). */
    fun stopValue(count: Int): Double = when (this) {
        NotStarted -> 0.0
        is AtStop -> index.toDouble()
        is Between -> index + fraction
        Finished -> maxOf(count - 1, 0).toDouble()
    }

    companion object {
        private fun dep(p: Place) = p.departure ?: p.arrival
        private fun arr(p: Place) = p.arrival ?: p.departure

        fun at(now: Instant, stops: List<Place>): TripProgress {
            val first = stops.firstOrNull() ?: return NotStarted
            val last = stops.last()
            dep(first)?.let { d -> if (now < d) return if (now >= (arr(first) ?: d)) AtStop(0) else NotStarted }
            arr(last)?.let { if (now >= it) return Finished }
            for (i in stops.indices) {
                val s = stops[i]
                val a = arr(s); val d = dep(s)
                if (a != null && d != null && now >= a && now < d) return AtStop(i)
                if (i + 1 >= stops.size || d == null) continue
                val an = arr(stops[i + 1]) ?: continue
                if (now < d || now >= an) continue
                val span = (an.toEpochMilli() - d.toEpochMilli()).toDouble()
                return Between(i, if (span > 0) (now.toEpochMilli() - d.toEpochMilli()) / span else 0.0)
            }
            return NotStarted
        }

        /** Estimated position, interpolated in a straight line between stops. */
        fun position(p: TripProgress, stops: List<Place>): LatLon? = when (p) {
            is AtStop -> stops[p.index].coordinate
            is Between -> {
                val a = stops[p.index]; val b = stops[p.index + 1]
                LatLon(a.lat + (b.lat - a.lat) * p.fraction, a.lon + (b.lon - a.lon) * p.fraction)
            }
            else -> null
        }

        /** Index of the next stop the vehicle has not left yet. */
        fun nextStopIndex(p: TripProgress, count: Int): Int? = when (p) {
            NotStarted -> 0
            is AtStop -> p.index
            is Between -> minOf(p.index + 1, count - 1)
            Finished -> null
        }
    }
}
