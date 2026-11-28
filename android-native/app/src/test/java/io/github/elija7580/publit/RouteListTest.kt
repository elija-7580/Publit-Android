// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.SmartRouting
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/** Same cases as iOS SmartRoutingTests (build 114): a much later look-alike route goes. */
class RouteListTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun time(m: Int) = t0.plusSeconds(m * 60L)
    private fun place(n: String) = Place(name = n, stopId = n, lat = 48.0, lon = 16.0)
    private fun leg(mode: String, from: String, to: String, start: Int, end: Int) =
        Leg(mode = mode, from = place(from), to = place(to), duration = (end - start) * 60, startTime = time(start), endTime = time(end),
            scheduledStartTime = time(start), scheduledEndTime = time(end), realTime = false,
            tripId = if (mode == "WALK") null else "$mode-$from-$start")
    private fun route(id: String, legs: List<Leg>) =
        Itinerary(id = id, duration = (legs.last().endTime.epochSecond - legs.first().startTime.epochSecond).toInt(),
            startTime = legs.first().startTime, endTime = legs.last().endTime, legs = legs,
            transfers = maxOf(0, legs.count { it.mode != "WALK" } - 1))
    private val head = listOf(leg("WALK", "Home", "A", 0, 6), leg("BUS", "A", "B", 6, 53), leg("BUS", "B", "C", 70, 121))

    @Test fun aMuchLaterLookAlikeGoes() {
        val walkOn = route("walk", head + leg("WALK", "C", "D", 122, 145))
        val rideOn = route("ride", head + listOf(leg("BUS", "C", "D", 165, 169), leg("WALK", "D", "D", 169, 171)))
        assertEquals(listOf("walk"), SmartRouting.pruned(listOf(walkOn, rideOn)).map { it.id })
        // Only 5 min earlier for 20 min more walking: both stay.
        val close = route("close", head + listOf(leg("BUS", "C", "D", 140, 148), leg("WALK", "D", "D", 148, 150)))
        assertEquals(2, SmartRouting.pruned(listOf(walkOn, close)).size)
    }
}
