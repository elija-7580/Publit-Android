// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import java.time.Instant

/**
 * Build your own connection, leg by leg (RouteBuilder.swift on iOS, #91/#142/#143): keep the first legs
 * of a route and plan the rest again, from where a ride starts or from a new place to change. Pure logic.
 */
object RouteBuilder {
    /** Where and when the search for the rest starts when a route is cut at one of its legs. */
    data class Restart(val from: Place, val to: Place, val earliest: Instant)

    /** The rest must start where the kept legs end, within this many metres. */
    const val JOIN_RADIUS = 400.0

    fun restart(route: Itinerary, index: Int): Restart? {
        if (index !in route.legs.indices) return null
        val earliest = if (index == 0) route.startTime else route.legs[index - 1].endTime
        return Restart(route.legs[index].from, route.legs.last().to, earliest)
    }

    /** Whether `rest` can follow the kept legs: it starts where they end and not before they arrive. */
    fun fits(rest: Itinerary, kept: List<Leg>): Boolean {
        val first = rest.legs.firstOrNull() ?: return false
        val last = kept.lastOrNull() ?: return true
        return !first.startTime.isBefore(last.endTime) && last.to.coordinate.distanceTo(first.from.coordinate) <= JOIN_RADIUS
    }

    fun make(legs: List<Leg>): Itinerary? {
        val first = legs.firstOrNull() ?: return null
        val last = legs.last()
        val rides = legs.count { it.isTransit }
        return Itinerary(id = "built|" + legs.joinToString("|") { "${it.mode}~${it.tripId ?: it.from.name}~${(it.scheduledStartTime ?: it.startTime).epochSecond}" },
            duration = (last.endTime.epochSecond - first.startTime.epochSecond).toInt(),
            startTime = first.startTime, endTime = last.endTime, transfers = maxOf(0, rides - 1), legs = legs)
    }

    /** `route` up to (not including) leg `index`, continued with `rest`; null when it does not fit. */
    fun splice(route: Itinerary, index: Int, rest: Itinerary): Itinerary? {
        if (index !in route.legs.indices) return null
        val kept = route.legs.subList(0, index)
        if (!fits(rest, kept)) return null
        return make(kept + rest.legs)
    }

    /**
     * Leave earlier (CatchEarlier): `way` to where leg `index` starts, then the route from that leg on as it is
     * (RouteBuilder.lead on iOS). Null when `way` arrives after the leg leaves or ends somewhere else.
     */
    fun lead(way: Itinerary, route: Itinerary, index: Int): Itinerary? {
        if (index <= 0 || index !in route.legs.indices) return null
        val end = way.legs.lastOrNull() ?: return null
        val rest = route.legs.subList(index, route.legs.size)
        if (rest[0].startTime.isBefore(end.endTime) || end.to.coordinate.distanceTo(rest[0].from.coordinate) > JOIN_RADIUS) return null
        return make(way.legs + rest)
    }

    private fun sorted(list: List<Itinerary>): List<Itinerary> {
        val seen = HashSet<String>()
        return list.filter { seen.add(it.routeKey) }.sortedWith(compareBy<Itinerary>({ it.endTime }, { it.startTime }))
    }

    /** The ways to go on from leg `index` ("Go on another way"), each as the whole route, earliest arrival first. */
    fun choices(route: Itinerary, index: Int, found: List<Itinerary>): List<Itinerary> =
        sorted(found.mapNotNull { splice(route, index, it) })

    // Choose where to change (#143)

    /** The same stop: by ID, else by name within a few hundred metres. */
    fun samePlace(a: Place, b: Place): Boolean {
        if (a.stopId != null && a.stopId == b.stopId) return true
        return a.name == b.name && a.coordinate.distanceTo(b.coordinate) <= JOIN_RADIUS
    }

    /** Where the ride can be left: every stop after boarding, from the vehicle's whole trip when known. */
    fun exitStops(leg: Leg, trip: Leg?): List<Place> {
        var stops = leg.intermediateStops.orEmpty() + leg.to
        if (trip != null) {
            val at = trip.allStops.indexOfFirst { samePlace(it, leg.from) }
            if (at >= 0) trip.allStops.drop(at + 1).takeIf { it.isNotEmpty() }?.let { stops = it }
        }
        return stops.filter { it.cancelled != true }
    }

    /** The way to the new change point that can follow the kept legs: the same vehicle first, else the earliest arrival. */
    fun wayToChange(route: Itinerary, index: Int, tripId: String?, found: List<Itinerary>): Itinerary? {
        if (index !in route.legs.indices) return null
        val kept = route.legs.subList(0, index)
        val usable = found.filter { fits(it, kept) }
        if (tripId != null) usable.firstOrNull { w -> w.transitLegs.size == 1 && w.transitLegs[0].tripId == tripId }?.let { return it }
        return usable.minWithOrNull(compareBy<Itinerary>({ it.endTime }, { it.startTime }))
    }

    /** The route with its change moved: legs before `index`, `way` to the new point, `rest` on to the destination. */
    fun changeAt(route: Itinerary, index: Int, way: Itinerary, rest: Itinerary): Itinerary? {
        val first = splice(route, index, way) ?: return null
        if (!fits(rest, first.legs)) return null
        return make(first.legs + rest.legs)
    }

    fun choices(route: Itinerary, index: Int, way: Itinerary, found: List<Itinerary>): List<Itinerary> =
        sorted(found.mapNotNull { changeAt(route, index, way, it) })
}
