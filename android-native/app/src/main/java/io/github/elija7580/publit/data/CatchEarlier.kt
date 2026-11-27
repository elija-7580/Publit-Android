// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import java.time.Instant

/**
 * A change on a route no longer works (or hardly): leave earlier and still catch the same connection
 * (CatchEarlier.swift on iOS, build 109). The router plans with the timetable; where live times only come from
 * another operator afterwards (LiveOverlay: Wiener Linien, ÖBB, …), a late feeder can break a change on a route
 * that has not started yet. The router never offers the earlier feeder by itself: it leaves earlier and arrives
 * at the same time. Example (27 Nov 2026): U1 → U4 → REX to Krems, the U4 late at Heiligenstadt.
 */
object CatchEarlier {
    /** Below this chance a change is worth rescuing. */
    const val RESCUE_BELOW = 0.5
    /** A way there counts as safe from this chance on (Change.Level.LIKELY). */
    const val SAFE_FROM = 0.7
    /** Arrive at the connection's platform this long before it leaves. */
    const val PLATFORM_MARGIN_SECONDS = 120L

    data class Target(val outgoingIndex: Int, val outgoing: Leg, val stopName: String, val chance: Double) {
        val latestArrival: Instant get() = outgoing.startTime.minusSeconds(PLATFORM_MARGIN_SECONDS)
    }

    /** The route that still makes it, the connection it catches, and how many minutes earlier it leaves. */
    data class Rescue(val route: Itinerary, val connection: Leg, val minutesEarlier: Int)

    /** The first change ahead that no longer works or is unlikely, while the route has not started yet. */
    fun target(route: Itinerary, changes: List<SmartRouting.Change>, now: Instant): Target? {
        if (!route.startTime.isAfter(now) || route.legs.size < 2) return null
        val c = changes.firstOrNull { it.source != SmartRouting.Change.Source.ThroughRun && (it.spareMinutes < 0 || it.chance < RESCUE_BELOW) } ?: return null
        if (c.outgoingIndex <= 0 || !c.outgoing.startTime.minusSeconds(PLATFORM_MARGIN_SECONDS).isAfter(now)) return null
        return Target(c.outgoingIndex, c.outgoing, c.stopName, c.chance)
    }

    /**
     * Out of the ways found to the connection's platform: the route that takes one and then goes on as before.
     * The latest way that makes every change up to the connection safely; else the surest one, if clearly better.
     */
    fun pick(ways: List<Itinerary>, route: Itinerary, target: Target, now: Instant, changes: (Itinerary) -> List<SmartRouting.Change>): Itinerary? {
        val candidates = ways.mapNotNull { way ->
            if (way.startTime.isBefore(now) || way.endTime.isAfter(target.outgoing.startTime)) return@mapNotNull null
            val joined = RouteBuilder.lead(way, route, target.outgoingIndex) ?: return@mapNotNull null
            if (joined.routeKey == route.routeKey) return@mapNotNull null
            val ahead = changes(joined).filter { it.outgoingIndex <= way.legs.size }
            if (ahead.any { it.spareMinutes < 0 }) return@mapNotNull null
            joined to (ahead.minOfOrNull { it.chance } ?: 1.0)
        }
        candidates.filter { it.second >= SAFE_FROM }.maxWithOrNull(compareBy({ it.first.startTime }, { it.second }))?.let { return it.first }
        val surest = candidates.maxByOrNull { it.second } ?: return null
        return if (surest.second >= RESCUE_BELOW && surest.second >= target.chance + 0.2) surest.first else null
    }

    fun minutesEarlier(rescued: Itinerary, route: Itinerary): Int =
        maxOf(0, Math.round((route.startTime.epochSecond - rescued.startTime.epochSecond) / 60.0).toInt())

    /** Plans the way to the connection (`plan` applies live times) and picks one. Null when nothing is needed or works. */
    suspend fun search(route: Itinerary, plan: suspend (from: Place, to: Place, arriveBy: Instant) -> List<Itinerary>,
                       now: Instant = Instant.now()): Rescue? {
        val target = target(route, SmartRouting.changes(route, now), now) ?: return null
        val start = route.legs.firstOrNull()?.from ?: return null
        val ways = runCatching { plan(start, target.outgoing.from, target.latestArrival) }.getOrNull() ?: return null
        val rescued = pick(ways, route, target, now) { SmartRouting.changes(it, now) } ?: return null
        return Rescue(rescued, target.outgoing, minutesEarlier(rescued, route))
    }
}
