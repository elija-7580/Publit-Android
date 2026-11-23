// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.data

import java.time.Instant
import java.time.ZoneId

/**
 * "Last connection": the latest you can still leave and get there tonight (LastConnection.swift on iOS).
 *
 * Asked as "arrive by" the end of the service day (4 a.m. the next morning), so the routes found are the
 * last ones that still arrive in the night, night buses included. A route that only arrives the next
 * morning (sitting at a station overnight) is not a last connection.
 */
object LastConnection {
    /** The service day ends at 4 a.m.: after midnight, "tonight" is still the day before. */
    const val DAY_END_HOUR = 4

    /** Arrive-by time for the last connection of the service day [now] falls in. */
    fun deadline(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant {
        val local = now.atZone(zone)
        val day = if (local.hour < DAY_END_HOUR) local.toLocalDate() else local.toLocalDate().plusDays(1)
        return day.atTime(DAY_END_HOUR, 0).atZone(zone).toInstant()
    }

    /** Routes that have not left yet (a minute's grace) and are a ride, in departure order. */
    fun upcoming(list: List<Itinerary>, now: Instant = Instant.now()): List<Itinerary> =
        list.filter { !it.isDirect && !it.startTime.isBefore(now.minusSeconds(60)) }
            .sortedWith(compareBy<Itinerary> { it.startTime }.thenBy { it.endTime })

    /** The route that leaves last; a cancelled one never counts. */
    fun last(list: List<Itinerary>): Itinerary? =
        list.filter { !it.isCancelled && !it.isDirect }
            .maxWithOrNull(compareBy<Itinerary> { it.startTime }.thenByDescending { it.endTime })
}
