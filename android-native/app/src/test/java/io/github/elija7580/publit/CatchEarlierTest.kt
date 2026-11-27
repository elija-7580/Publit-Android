// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.CatchEarlier
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RouteBuilder
import io.github.elija7580.publit.data.SmartRouting
import io.github.elija7580.publit.data.WalkModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Same cases as iOS CatchEarlierTests (build 109): a late subway misses the train, an earlier one still makes it. */
class CatchEarlierTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun time(m: Int) = t0.plusSeconds(m * 60L)
    private fun place(n: Int) = Place(name = "S$n", stopId = "s$n", lat = 48 + n * 0.01, lon = 16.0)
    private fun leg(mode: String, from: Int, to: Int, start: Int, end: Int, delay: Int = 0) =
        Leg(mode = mode, from = place(from), to = place(to), duration = (end - start) * 60, startTime = time(start + delay), endTime = time(end + delay),
            scheduledStartTime = time(start), scheduledEndTime = time(end), realTime = delay != 0,
            tripId = if (mode == "WALK") null else "$mode-$from-$start")
    private fun route(id: String, legs: List<Leg>) =
        Itinerary(id = id, duration = (legs.last().endTime.epochSecond - legs.first().startTime.epochSecond).toInt(),
            startTime = legs.first().startTime, endTime = legs.last().endTime, legs = legs)
    private fun changes(it: Itinerary, now: Instant) =
        SmartRouting.changes(it, now, history = { null }, walkModel = WalkModel.PRIOR, extra = 0.0, learned = null)

    /** Walk to S1, subway to S2 (6 min late), train from S2 at 18 to S3. */
    private val broken = route("A", listOf(leg("WALK", 0, 1, 0, 4), leg("SUBWAY", 1, 2, 5, 15, delay = 6), leg("REGIONAL_RAIL", 2, 3, 18, 50)))
    private fun way(id: String, start: Int, end: Int) = route(id, listOf(leg("WALK", 0, 1, start, start + 4), leg("SUBWAY", 1, 2, start + 5, end)))

    @Test fun aBrokenChangeAheadIsTheTarget() {
        val now = time(-20)
        val target = CatchEarlier.target(broken, changes(broken, now), now)!!
        assertEquals(2, target.outgoingIndex)
        assertEquals("S2", target.stopName)
        assertEquals(time(16), target.latestArrival)
        assertNull(CatchEarlier.target(broken, changes(broken, time(1)), time(1)))
        val fine = route("B", listOf(leg("WALK", 0, 1, 0, 4), leg("SUBWAY", 1, 2, 5, 10), leg("REGIONAL_RAIL", 2, 3, 18, 50)))
        assertNull(CatchEarlier.target(fine, changes(fine, now), now))
    }

    @Test fun aWayThereLeadsIntoTheSameConnection() {
        val joined = RouteBuilder.lead(way("W", -2, 13), broken, 2)!!
        assertEquals(listOf("WALK", "SUBWAY", "REGIONAL_RAIL"), joined.legs.map { it.mode })
        assertEquals(time(-2), joined.startTime)
        assertEquals(broken.endTime, joined.endTime)
        assertNull(RouteBuilder.lead(way("L", 5, 19), broken, 2))
        assertNull(RouteBuilder.lead(way("W", -2, 13), broken, 0))
    }

    @Test fun theLatestSafeWayIsPicked() {
        val now = time(-20)
        val target = CatchEarlier.target(broken, changes(broken, now), now)!!
        val ways = listOf(way("gone", -25, -10), way("early", -10, 5), way("good", -2, 13), way("tight", 3, 18))
        val picked = CatchEarlier.pick(ways, broken, target, now) { changes(it, now) }
        assertNotNull(picked)
        assertEquals(time(-2), picked!!.startTime)
        assertEquals(2, CatchEarlier.minutesEarlier(picked, broken))
        assertNull(CatchEarlier.pick(listOf(ways[0]), broken, target, now) { changes(it, now) })
    }
}
