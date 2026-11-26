// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * Plan from the vehicle you are riding (OnBoardPlan.swift on iOS, Publit#192): you are already on a tram, bus
 * or train and want to go somewhere else. The idea comes from BudapestGO, the app of BKK (Budapest), which
 * calls it "on-board journey planning".
 *
 * One search starts at the next stop the vehicle reaches, at the time it gets there. The router may keep you on
 * the same vehicle (it boards it there again) or let you change at any later stop. The ride up to the next stop
 * is put in front, and the same vehicle is one ride. Pure logic.
 */
object OnBoardPlan {
    /** `from`: the stop the ride counts from (last one left or the one it stands at); `next`: where the search starts, at `at`. */
    data class Position(val from: Int, val next: Int, val at: Instant)

    /** Where you are on a vehicle's ride now; null when it has arrived at its last stop. */
    fun position(stops: List<Place>, now: Instant): Position? {
        if (stops.size < 2) return null
        val (from, next) = when (val p = TripProgress.at(now, stops)) {
            TripProgress.Finished -> return null
            TripProgress.NotStarted -> 0 to 0
            is TripProgress.AtStop -> p.index to p.index
            is TripProgress.Between -> p.index to p.index + 1
        }
        val stop = stops[next]
        val arrival = stop.arrival ?: stop.departure ?: now
        return Position(from, next, maxOf(now, arrival))
    }

    /** The ride of `trip` from stop `from` to stop `to` as a leg of its own; null when `to` is not after `from`. */
    fun ride(trip: Leg, from: Int, to: Int): Leg? {
        val stops = trip.allStops
        if (from < 0 || to >= stops.size || to <= from) return null
        val a = stops[from]; val b = stops[to]
        val start = a.departure ?: a.arrival ?: trip.startTime
        val end = b.arrival ?: b.departure ?: trip.endTime
        val geometry = trip.legGeometry
        val path = geometry?.let { Polyline.decode(it.points, it.precision) }.orEmpty()
        val cut = cut(path, a.coordinate, b.coordinate)
        return trip.copy(from = a, to = b, intermediateStops = stops.subList(from + 1, to).toList(),
            startTime = start, endTime = end,
            scheduledStartTime = a.scheduledDeparture ?: a.scheduledArrival ?: start,
            scheduledEndTime = b.scheduledArrival ?: b.scheduledDeparture ?: end,
            duration = maxOf(0, (end.epochSecond - start.epochSecond).toInt()), distance = null,
            legGeometry = EncodedPolyline(Polyline.encode(cut, geometry?.precision ?: 6), geometry?.precision ?: 6, cut.size),
            alternatives = null, fareTransferIndex = null, effectiveFareLegIndex = null)
    }

    /** The part of `path` between the points closest to `a` and (after it) to `b`. */
    fun cut(path: List<LatLon>, a: LatLon, b: LatLon): List<LatLon> {
        if (path.size < 2) return listOf(a, b)
        val i = path.indices.minBy { path[it].distanceTo(a) }
        val j = (i until path.size).minBy { path[it].distanceTo(b) }
        return if (j > i) path.subList(i, j + 1).toList() else listOf(a, b)
    }

    /** What a route from the vehicle is made of, so its live times can be fetched again (`refresh`). */
    @Serializable
    data class Ref(val tripId: String, val from: Int, val to: Int, val rest: String? = null, val first: Int = 0)

    private val json = Json { ignoreUnknownKeys = true }
    const val PREFIX = "onboard:"

    fun encodeId(ref: Ref): String = PREFIX + java.util.Base64.getUrlEncoder().withoutPadding()
        .encodeToString(json.encodeToString(Ref.serializer(), ref).toByteArray())

    fun decodeId(id: String): Ref? = if (!id.startsWith(PREFIX)) null else runCatching {
        json.decodeFromString(Ref.serializer(), String(java.util.Base64.getUrlDecoder().decode(id.removePrefix(PREFIX))))
    }.getOrNull()

    private fun make(ride: Leg, rest: List<Leg>, ref: Ref): Itinerary {
        val all = listOf(ride) + rest
        val end = all.last().endTime
        return Itinerary(id = encodeId(ref), duration = (end.epochSecond - ride.startTime.epochSecond).toInt(),
            startTime = ride.startTime, endTime = end, transfers = maxOf(0, all.count { it.isTransit } - 1), legs = all)
    }

    /**
     * One route found from the next stop, as a route from the vehicle: the ride so far first. When it rides on
     * with the same vehicle that is one ride; when it gets off at the next stop the ride ends there.
     */
    fun route(found: Itinerary, trip: Leg, position: Position): Itinerary? {
        val tripId = trip.tripId ?: return null
        val stops = trip.allStops
        if (position.from !in stops.indices || position.next !in stops.indices) return null
        val legs = found.legs
        // Short walks at the start stop (from a point to the platform) do not count.
        var first = 0
        while (first < legs.size - 1 && !legs[first].isTransit &&
            legs[first].from.coordinate.distanceTo(legs[first].to.coordinate) < 150) first++
        val head = legs.getOrNull(first)
        val off = if (head != null && head.isTransit && head.tripId == tripId)
            (position.next until stops.size).firstOrNull { RouteBuilder.samePlace(stops[it], head.to) } else null
        if (off != null && off > position.from) {
            val ride = ride(trip, position.from, off) ?: return null
            val rest = legs.drop(first + 1)
            return make(ride, rest, Ref(tripId, position.from, off, found.id.takeIf { rest.isNotEmpty() }, first + 1))
        }
        if (legs.isEmpty() || legs.first().startTime < position.at.minusSeconds(60)) return null
        // Gets off at the next stop (none to ride when the vehicle stands there: you get off now).
        val ride = ride(trip, position.from, position.next) ?: return found
        return make(ride, legs, Ref(tripId, position.from, position.next, found.id, 0))
    }

    /**
     * The routes from the vehicle: each once, earliest arrival first, then fewer changes. A route that arrives
     * later with as many changes or more is no choice on board (getting off to wait for a later vehicle the same way).
     */
    fun routes(found: List<Itinerary>, trip: Leg, position: Position): List<Itinerary> {
        val seen = HashSet<String>()
        val all = found.mapNotNull { route(it, trip, position) }.filter { seen.add(it.routeKey) }
            .sortedWith(compareBy<Itinerary>({ it.endTime }, { it.transfers }, { it.startTime }))
        val kept = ArrayList<Itinerary>()
        for (r in all) if (kept.none { it.endTime <= r.endTime && it.transfers <= r.transfers }) kept += r
        return kept
    }

    /** The route of an on-board ID put together again from the vehicle's trip and the planned rest, as fetched now. */
    fun refreshed(ref: Ref, trip: Itinerary, rest: Itinerary?): Itinerary? {
        val tripLeg = trip.legs.firstOrNull { it.isTransit } ?: return null
        val ride = ride(tripLeg, ref.from, ref.to) ?: return null
        val restLegs = rest?.legs?.drop(ref.first).orEmpty()
        return make(ride, restLegs, ref.copy(rest = rest?.id ?: ref.rest))
    }
}
