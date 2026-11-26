// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.StreetComparison
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Walking as a real choice (iOS `FilterTests`, "Walking as a real choice", Publit#110): when the walk joins the routes. */
class WalkChoiceTest {
    private val t0: Instant = Instant.parse("2026-11-26T08:00:00Z")
    private fun at(min: Int): Instant = t0.plusSeconds(min * 60L)
    private val a: Place = Place(name = "A", lat = 48.14, lon = 11.56)
    private val b: Place = Place(name = "B", lat = 48.15, lon = 11.58)

    private fun street(mode: String, from: Int, to: Int, id: String = mode.lowercase()): Itinerary =
        Itinerary(id = id, duration = (to - from) * 60, startTime = at(from), endTime = at(to),
            legs = listOf(Leg(mode = mode, from = a, to = b, duration = (to - from) * 60, startTime = at(from), endTime = at(to), distance = 1200.0)))
    private fun ride(from: Int, to: Int, cancelled: Boolean = false, id: String = "ride"): Itinerary =
        Itinerary(id = id, duration = (to - from) * 60, startTime = at(from), endTime = at(to),
            legs = listOf(Leg(mode = "BUS", from = a, to = b, duration = (to - from) * 60, startTime = at(from), endTime = at(to),
                scheduledStartTime = at(from), routeShortName = "52", headsign = "East", tripId = "t-$id", cancelled = if (cancelled) true else null)))

    @Test fun walkIsListedWhenNoRideGetsThereSooner() {
        // 17 min on foot; the 6 min bus only leaves in 15 minutes and arrives after the walk.
        val lateWalk: Itinerary = street("WALK", 0, 17)
        val lateRide: Itinerary = ride(15, 21)
        assertTrue(StreetComparison.walkIsAChoice(lateWalk, listOf(lateRide)))
        assertEquals(listOf("walk", "ride"), StreetComparison.merge(listOf(lateRide), listOf(lateWalk)).map { it.id })
        // The same kind of walk loses when the bus leaves soon and is there first.
        val soonWalk: Itinerary = street("WALK", 0, 40)
        val soonRide: Itinerary = ride(5, 20)
        assertFalse(StreetComparison.walkIsAChoice(soonWalk, listOf(soonRide)))
        assertEquals(listOf("ride"), StreetComparison.merge(listOf(soonRide), listOf(soonWalk)).map { it.id })
        // Arriving at the same minute, the ride that leaves later wins.
        assertFalse(StreetComparison.walkIsAChoice(street("WALK", 0, 40), listOf(ride(20, 40))))
    }

    @Test fun walkIsListedForArriveByWhenItLeavesLater() {
        // Arrive by minute 60: the ride has to leave at 10, the walk at 20.
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 20, 60), listOf(ride(10, 50))))
        // The ride leaves after the walk and still arrives earlier: no walk in the list.
        assertFalse(StreetComparison.walkIsAChoice(street("WALK", 20, 60), listOf(ride(35, 55))))
    }

    @Test fun shortOrAboutAsFastWalksStayListedAndCancelledRidesDoNotCount() {
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 0, 10), listOf(ride(1, 4))))
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 0, 25), listOf(ride(2, 22))))
        // Exactly 25 % longer still counts, a minute more does not (the ride is there first).
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 0, 25), listOf(ride(2, 22), ride(3, 23, id = "second"))))
        assertFalse(StreetComparison.walkIsAChoice(street("WALK", 0, 26), listOf(ride(2, 22))))
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 0, 40), listOf(ride(5, 20, cancelled = true))))
        // No ride at all: walking is the way.
        assertTrue(StreetComparison.walkIsAChoice(street("WALK", 0, 40), emptyList()))
    }

    @Test fun carAndBikeNeverJoinTheRouteList() {
        val direct: List<Itinerary> = listOf(street("CAR", 0, 5), street("BIKE", 0, 9), street("WALK", 0, 30, id = "slow-walk"), street("WALK", 0, 12, id = "walk"))
        // The fastest walk is the one that counts.
        assertEquals("walk", StreetComparison.walk(direct)?.id)
        assertNull(StreetComparison.walk(listOf(street("CAR", 0, 5), street("BIKE", 0, 9))))
        assertEquals("WALK", StreetComparison.streetMode(direct[3]))
        assertNull(StreetComparison.streetMode(ride(0, 5)))
        val rides: List<Itinerary> = listOf(ride(20, 30))
        assertEquals(listOf("walk", "ride"), StreetComparison.merge(rides, direct).map { it.id })
        // Nothing to walk: the rides as they are; no ride: the walk alone.
        assertEquals(listOf("ride"), StreetComparison.merge(rides, listOf(street("CAR", 0, 5))).map { it.id })
        assertEquals(listOf("walk"), StreetComparison.merge(emptyList(), direct).map { it.id })
        assertTrue(StreetComparison.merge(emptyList(), emptyList()).isEmpty())
    }
}
