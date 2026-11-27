// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.LateTrains
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Same case as iOS LateTrainsTests (build 110): a late train whose timetable time has passed still shows. */
class LateTrainsTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun time(m: Int) = t0.plusSeconds(m * 60L)
    private fun place(n: Int) = Place(name = "S$n", stopId = "s$n", lat = 48 + n * 0.01, lon = 16.0)
    private fun train(start: Int, delay: Int, cancelled: Boolean = false): Itinerary {
        val ride = Leg(mode = "REGIONAL_RAIL", from = place(1), to = place(2), duration = 1800, startTime = time(start + delay),
            endTime = time(start + 30 + delay), scheduledStartTime = time(start), scheduledEndTime = time(start + 30),
            realTime = delay != 0, tripId = "R-$start", liveSource = "oebb", cancelled = if (cancelled) true else null)
        return Itinerary(id = "R$start", duration = 1800, startTime = ride.startTime, endTime = ride.endTime, legs = listOf(ride))
    }

    @Test fun anEarlierSearchFindsTheLateTrainsStillToCatch() {
        val base = time(0)
        val found = listOf(train(-20, 0), train(-10, 15), train(-5, 2), train(-8, 12, cancelled = true), train(5, 0))
        val late = LateTrains.stillReachable(found, base, now = base, known = emptySet())
        assertEquals(listOf("R-10"), late.map { it.id })
        assertTrue(LateTrains.isLate(train(-10, 15)))
        assertFalse(LateTrains.isLate(train(5, 0)))
        // Already listed: not twice.
        assertTrue(LateTrains.stillReachable(found, base, base, setOf(train(-10, 15).routeKey)).isEmpty())
    }
}
