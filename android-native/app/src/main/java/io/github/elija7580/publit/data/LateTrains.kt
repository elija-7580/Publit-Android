// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import java.time.Instant

/**
 * Late trains in a route search (iOS LateTrains, build 110). Transitous plans Austria and other regions on
 * the timetable; the delays come from other operators afterwards (LiveOverlay). So a train whose timetable
 * departure has passed is missing from a "now" search although it is late and still leaves (feedback
 * 27 Nov 2026: "the train is late, the app shows it on time, then it is gone").
 */
object LateTrains {
    /** How far back a search looks for trains that are late enough to still be caught (s). */
    const val LOOKBACK_SECONDS = 30 * 60L

    private fun Leg.scheduled(): Instant = scheduledStartTime ?: startTime

    /** The first ride leaves at least a minute after its timetable time. */
    fun isLate(it: Itinerary): Boolean {
        val first = it.transitLegs.firstOrNull() ?: return false
        return first.startTime.epochSecond - first.scheduled().epochSecond >= 60
    }

    /**
     * Out of a search from LOOKBACK before [base], with live times applied: the routes whose first ride was
     * due before [base] but is late enough that the route has not left yet. Routes already listed ([known]
     * route keys) are left out.
     */
    fun stillReachable(found: List<Itinerary>, base: Instant, now: Instant, known: Set<String>): List<Itinerary> {
        val seen = known.toMutableSet()
        return found.filter { it ->
            val first = it.transitLegs.firstOrNull() ?: return@filter false
            first.scheduled() < base && isLate(it) && !it.isCancelled && it.startTime >= now &&
                it.startTime >= base.minusSeconds(60) && seen.add(it.routeKey)
        }
    }
}
