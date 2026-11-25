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

        /**
         * Estimated position between stops: along the ride's shape when there is one, else in a straight line
         * (TripProgress.position(path:) on iOS, build 99). A long-distance bus with few stops (Vienna to Venice)
         * went straight across the country, far away from its road. The shape counts only when both stops lie
         * on it (within 2 km) and in their order.
         */
        fun position(p: TripProgress, stops: List<Place>, path: List<LatLon> = emptyList()): LatLon? = when (p) {
            is AtStop -> stops.getOrNull(p.index)?.coordinate
            is Between -> if (p.index < 0 || p.index + 1 >= stops.size) null else {
                val a = stops[p.index]; val b = stops[p.index + 1]
                onShape(a.coordinate, b.coordinate, p.fraction, path)
                    ?: LatLon(a.lat + (b.lat - a.lat) * p.fraction, a.lon + (b.lon - a.lon) * p.fraction)
            }
            else -> null
        }

        private fun onShape(a: LatLon, b: LatLon, fraction: Double, path: List<LatLon>): LatLon? {
            if (path.size < 2) return null
            val from = PathGeometry.locate(a, path) ?: return null
            val to = PathGeometry.locate(b, path) ?: return null
            if (from.offset >= 2_000 || to.offset >= 2_000 || to.along <= from.along) return null
            return PathGeometry.point(from.along + (to.along - from.along) * fraction, path)
        }

        /**
         * Where you are along the ride from your own position (TripProgress.located on iOS): your fix laid onto
         * the line, stops in their order. Null when the fix is more than `maxOffset` metres away from the line.
         */
        fun located(p: LatLon, stops: List<Place>, path: List<LatLon>, maxOffset: Double = 200.0): TripProgress? {
            if (stops.size < 2) return null
            val line = if (path.size >= 2) path else stops.map { it.coordinate }
            val me = PathGeometry.locate(p, line)?.takeIf { it.offset <= maxOffset } ?: return null
            val marks = ArrayList<Double>(stops.size)
            var last = 0.0
            for (s in stops) { last = maxOf(last, PathGeometry.locate(s.coordinate, line)?.along ?: last); marks += last }
            // At a stop when within a platform's length of it.
            val nearest = marks.indices.minByOrNull { kotlin.math.abs(marks[it] - me.along) } ?: 0
            if (kotlin.math.abs(marks[nearest] - me.along) < 60) return AtStop(nearest)
            if (me.along <= marks[0]) return AtStop(0)
            for (i in 0 until marks.size - 1) if (me.along < marks[i + 1]) {
                val span = marks[i + 1] - marks[i]
                return Between(i, if (span > 0) (me.along - marks[i]) / span else 0.0)
            }
            return AtStop(stops.size - 1)
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

/**
 * Where a vehicle's position on the map comes from, said next to it (VehiclePositionSource on iOS).
 * Publit has no GPS positions from vehicles: Transitous does not pass them on, so there is no "GPS" label.
 */
enum class VehiclePositionSource(val label: String) {
    /** Placed between stops by the live (real-time) stop times. */
    LIVE_TIMES("Position estimated from live stop times."),
    /** Placed by the timetable: the operator sends no live data for this trip. */
    TIMETABLE("No live data for this trip. Position estimated from the timetable."),
    /** Not under way (not left the first stop yet, or arrived): no position to show. */
    UNAVAILABLE("Vehicle position unavailable right now.");

    companion object {
        fun of(progress: TripProgress, realTime: Boolean): VehiclePositionSource = when (progress) {
            TripProgress.NotStarted, TripProgress.Finished -> UNAVAILABLE
            else -> if (realTime) LIVE_TIMES else TIMETABLE
        }

        /** The stops the vehicle has not left yet ("Next stops" under "Track vehicle"). */
        fun upcoming(progress: TripProgress, stops: List<Place>): List<Place> {
            val first = TripProgress.nextStopIndex(progress, stops.size) ?: return emptyList()
            return if (first in stops.indices) stops.subList(first, stops.size) else emptyList()
        }
    }
}
