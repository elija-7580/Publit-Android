// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Routes behind a long walk to the stop say so above the list (iOS `Reach.longWalkNote`, Publit#178). */
class LongWalkNoteTest {
    private val t = Instant.parse("2026-11-24T07:00:00Z")
    private val p = Place(name = "P", lat = 45.44, lon = 12.42)
    private fun leg(mode: String, s: Int) = Leg(mode = mode, from = p, to = p, duration = s, startTime = t, endTime = t)
    private fun route(vararg legs: Leg) =
        Itinerary(id = legs.joinToString("-") { "${it.mode}${it.duration}" }, duration = legs.sumOf { it.duration }, startTime = t, endTime = t, legs = legs.toList())

    // Cavallino-Treporti to San Zaccaria: 40 min on foot to the ferry at Punta Sabbioni (the iOS fixture plan_long_walk).
    private val toTheFerry = route(leg("WALK", 2400), leg("FERRY", 2100), leg("WALK", 180))

    @Test fun longWalkIsMeasuredAndNamed() {
        assertEquals(2400, Reach.longestAccessWalk(listOf(toTheFerry, route(leg("WALK", 600), leg("BUS", 900)))))
        val note = Reach.longWalkNote(listOf(toTheFerry))!!
        assertTrue(note, note.contains("40"))
    }

    @Test fun theWalkFromTheLastStopCountsToo() {
        // Two walks in a row after the last ride add up; the change in the middle does not count.
        val r = route(leg("WALK", 300), leg("BUS", 600), leg("WALK", 2500), leg("TRAM", 600), leg("WALK", 1000), leg("WALK", 1100))
        assertEquals(2100, Reach.longestAccessWalk(listOf(r)))
        assertTrue(Reach.longWalkNote(listOf(r))!!.contains("35"))
    }

    @Test fun noNoteForUsualWalksOrWalkOnly() {
        assertNull(Reach.longWalkNote(listOf(route(leg("WALK", 1800), leg("BUS", 600), leg("WALK", 240)))))
        assertNull(Reach.longWalkNote(emptyList()))
        // Walking the whole way is not "a long walk to the stop".
        assertEquals(0, Reach.longestAccessWalk(listOf(route(leg("WALK", 5400)))))
        assertNull(Reach.longWalkNote(listOf(route(leg("WALK", 5400)))))
    }

    @Test fun bikeOrCarToTheStopIsNotWalking() {
        val r = route(leg("BIKE", 2400), leg("WALK", 120), leg("REGIONAL_RAIL", 1800), leg("CAR", 2700))
        assertEquals(120, Reach.longestAccessWalk(listOf(r)))
        assertNull(Reach.longWalkNote(listOf(r)))
    }
}
