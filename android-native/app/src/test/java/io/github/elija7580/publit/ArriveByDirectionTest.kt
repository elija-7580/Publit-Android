// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.PlanResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Arrive by" must never list a route that runs from the destination to the start (iOS `ArriveByDirectionTests`, Publit#188). */
class ArriveByDirectionTest {
    // Saarbrücken, a point in the centre to "Universität Busterminal", arrive by 27.11.2026 09:30, asked with
    // slowDirect=true (smart routing) on 26.11.2026. Real answer, shortened (no shapes, no stops in between):
    // 9 routes, 4 of them start at the university and end at "END" in the centre. Same file as on iOS.
    private val from: LatLon = LatLon(49.2311, 6.9990)
    private val to: LatLon = LatLon(49.25746, 7.047351)
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun answer(): PlanResponse =
        json.decodeFromString(PlanResponse.serializer(), javaClass.classLoader!!.getResource("plan_arrive_by_slow_direct.json")!!.readText())

    @Test fun serverAnswerContainsRoutesTheWrongWayRound() {
        val res = answer()
        assertEquals(9, res.itineraries.size)
        val backwards = res.itineraries.filter { it.runsBackwards(from, to) }
        assertEquals(4, backwards.size)
        // All of them: board at or next to the destination, end on the start's coordinates.
        for (route in backwards) {
            assertEquals("END", route.legs[route.legs.size - 1].to.name)
            assertTrue(route.legs[0].from.coordinate.distanceTo(to) < 600)
            assertTrue(route.legs[route.legs.size - 1].to.coordinate.distanceTo(from) < 50)
        }
    }

    @Test fun onlyRoutesInTheAskedDirectionStay() {
        val res = answer()
        val usable = res.facing(from, to)
        assertEquals(listOf("slow-direct-0", "slow-direct-1", "slow-direct-2", "slow-direct-3", "slow-direct-5"), usable.itineraries.map { it.id })
        for (route in usable.itineraries) {
            assertEquals("START", route.legs[0].from.name)
            assertTrue(route.legs[route.legs.size - 1].to.name.startsWith("Universität Busterminal"))
            assertTrue(route.legs[route.legs.size - 1].to.coordinate.distanceTo(to) < 150)
        }
        // Paging goes on from the same cursors.
        assertEquals(res.previousPageCursor, usable.previousPageCursor)
        assertEquals(res.nextPageCursor, usable.nextPageCursor)
    }

    /** The way back asked on purpose (start and destination swapped) keeps exactly the other four. */
    @Test fun theWayBackIsNotBackwardsWhenAskedFor() {
        val back = answer().facing(to, from)
        assertEquals(4, back.itineraries.size)
        assertTrue(back.itineraries.all { it.legs[it.legs.size - 1].to.name == "END" })
    }

    /** Two platforms of one station: nothing is judged, a route from the platform nearer to the destination is still a route. */
    @Test fun nothingIsDroppedWhenStartAndDestinationAreClose() {
        val res = answer()
        val near = LatLon(from.lat + 0.001, from.lon)
        assertTrue(from.distanceTo(near) < 200)
        assertEquals(9, res.facing(from, near).itineraries.size)
        assertFalse(res.itineraries[8].runsBackwards(from, from))
        // An answer with nothing to drop comes back as it is.
        val right = res.facing(from, to)
        assertTrue(right.facing(from, to) === right)
    }
}
