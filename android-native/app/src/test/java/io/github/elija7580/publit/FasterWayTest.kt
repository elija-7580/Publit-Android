// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.GuideStep
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripAlternative
import io.github.elija7580.publit.data.TripGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** A better stop while walking to a ride (iOS `FasterWayTests`, Publit#110): when to look, and what counts as clearly faster. */
class FasterWayTest {
    private val t0 = Instant.parse("2026-11-25T06:00:00Z")
    private fun at(min: Int, sec: Int = 0): Instant = t0.plusSeconds(min * 60L + sec)
    private val p = Place(name = "P", lat = 48.14, lon = 11.56)

    private fun walk(from: Int, to: Int) =
        Leg(mode = "WALK", from = p, to = p, duration = (to - from) * 60, startTime = at(from), endTime = at(to))
    private fun ride(mode: String, trip: String?, from: Int, to: Int, line: String = "S1") =
        Leg(mode = mode, from = p, to = p, duration = (to - from) * 60, startTime = at(from), endTime = at(to),
            scheduledStartTime = at(from), routeShortName = line, headsign = "East", tripId = trip)
    private fun route(id: String, vararg legs: Leg) =
        Itinerary(id = id, duration = (legs.last().endTime.epochSecond - legs.first().startTime.epochSecond).toInt(),
            startTime = legs.first().startTime, endTime = legs.last().endTime, legs = legs.toList())

    // As the iOS fixture plan_muc: S-Bahn 06:17 (arrives 06:57), S-Bahn 06:18 (arrives 07:08),
    // U-Bahn 06:21 and bus (arrives 06:58).
    private val first = route("first", walk(12, 17), ride("SUBURBAN", "s-0617", 17, 55), walk(55, 57))
    private val slow = route("slow", walk(12, 18), ride("SUBURBAN", "s-0618", 18, 66), walk(66, 68))
    private val viaBus = route("viaBus", walk(15, 21), ride("SUBWAY", "u-0621", 21, 40), ride("BUS", "b-0643", 43, 56), walk(56, 58))
    private val all = listOf(first, slow, viaBus)

    @Test fun onlyClearlyEarlierArrivalsEarliestFirst() {
        val now = slow.startTime.minusSeconds(360)
        // On the slow S-Bahn: both others arrive ten minutes earlier; the trip itself is no other way.
        assertEquals(listOf("first", "viaBus"), TripAlternative.faster(all, slow, now).map { it.id })
        // One minute earlier is not worth turning round for.
        assertEquals(60, viaBus.endTime.epochSecond - first.endTime.epochSecond)
        assertTrue(TripAlternative.faster(all, viaBus, now).isEmpty())
        assertTrue(TripAlternative.faster(all, first, now).isEmpty())
        assertTrue(TripAlternative.faster(emptyList(), slow, now).isEmpty())
        // Exactly three minutes earlier counts, one second less does not.
        val threeEarlier = route("three", walk(12, 16), ride("SUBURBAN", "s-0616", 16, 65))
        assertEquals(listOf("three"), TripAlternative.faster(listOf(threeEarlier), slow, now).map { it.id })
        val almost = threeEarlier.copy(id = "almost", endTime = threeEarlier.endTime.plusSeconds(1))
        assertTrue(TripAlternative.faster(listOf(almost), slow, now).isEmpty())
    }

    @Test fun onlyWhatYouCanStillCatch() {
        // The 06:17 S-Bahn leaves in a few seconds: only the way via the bus is left.
        val now = at(17).minusSeconds(10)
        assertEquals(listOf("viaBus"), TripAlternative.faster(all, slow, now).map { it.id })
        // A cancelled ride is no way, and the same route found twice is offered once.
        val cancelled = first.copy(id = "cancelled", legs = first.legs.map { if (it.isTransit) it.copy(cancelled = true) else it })
        val early = slow.startTime.minusSeconds(360)
        assertEquals(listOf("viaBus"), TripAlternative.faster(listOf(cancelled, viaBus), slow, early).map { it.id })
        assertEquals(listOf("first"), TripAlternative.faster(listOf(first, first.copy(id = "again")), slow, early).map { it.id })
        assertEquals(1, TripAlternative.faster(all, slow, early, limit = 1).size)
    }

    @Test fun sameVehiclesAreNotAnotherWay() {
        // The trip's own vehicles with a fresher, earlier live arrival: still the same way.
        val fresher = slow.copy(id = "fresher", endTime = slow.endTime.minusSeconds(600))
        assertTrue(TripAlternative.faster(listOf(fresher), slow, slow.startTime.minusSeconds(360)).isEmpty())
        // Without a run id the line, its direction and the scheduled departure say which vehicle it is.
        val unnamed = route("unnamed", walk(12, 18), ride("SUBURBAN", null, 18, 66), walk(66, 68))
        val unnamedFresher = unnamed.copy(id = "unnamedFresher", endTime = unnamed.endTime.minusSeconds(600))
        assertTrue(TripAlternative.faster(listOf(unnamedFresher), unnamed, at(12)).isEmpty())
        // Rides of the trip that are over do not count: on the walk to the second ride, the second ride alone is the trip ahead.
        val twoRides = route("two", walk(0, 4), ride("SUBWAY", "u-1", 4, 10), walk(10, 14), ride("SUBWAY", "u-2", 20, 40))
        val onlySecond = route("second", walk(11, 14), ride("SUBWAY", "u-2", 20, 30))
        assertTrue(TripAlternative.faster(listOf(onlySecond), twoRides, at(11)).isEmpty())
    }

    @Test fun looksOnlyOnAWalkToARideWithTimeLeft() {
        // As the iOS fixture plan_walk_steps: walk 13:57 (4 min), U-Bahn 14:01, walk 14:03 (3 min), U-Bahn 14:07, walk 14:14 to the door.
        val it = route("steps", walk(0, 4), ride("SUBWAY", "u-a", 4, 6, "U2"), walk(6, 9), ride("SUBWAY", "u-b", 10, 17, "U6"), walk(17, 22))
        val steps = TripGuide.steps(it)
        val firstWalk = steps.first { s -> s.kind == GuideStep.Kind.WALK }
        val departs = it.legs[1].startTime
        assertTrue(TripAlternative.worthLooking(firstWalk, it, it.startTime))
        assertTrue(TripAlternative.worthLooking(firstWalk, it, departs.minusSeconds(180)))
        // Two minutes before it leaves you are at the stop.
        assertFalse(TripAlternative.worthLooking(firstWalk, it, departs.minusSeconds(120)))
        // A trip opened long before it starts is not slow because one could leave now.
        assertFalse(TripAlternative.worthLooking(firstWalk, it, it.startTime.minusSeconds(600)))
        // Waiting and riding are not walking.
        for (s in steps) if (s.kind != GuideStep.Kind.WALK) assertFalse(s.id, TripAlternative.worthLooking(s, it, it.startTime))
        // The walk between two rides counts, the walk at the end leads to no ride.
        val between = steps.first { s -> s.kind == GuideStep.Kind.WALK && s.legIndex == 2 }
        assertTrue(TripAlternative.worthLooking(between, it, it.legs[3].startTime.minusSeconds(200)))
        val last = steps.last { s -> s.kind == GuideStep.Kind.WALK }
        assertEquals(it.legs.size - 1, last.legIndex)
        assertFalse(TripAlternative.worthLooking(last, it, it.legs[4].startTime))
        // A ride that does not run is not walked to for a faster way: that is the card for what went wrong.
        val cancelledNext = it.copy(legs = it.legs.mapIndexed { i, l -> if (i == 1) l.copy(cancelled = true) else l })
        assertFalse(TripAlternative.worthLooking(firstWalk, cancelledNext, it.startTime))
    }

    @Test fun saysByHowManyMinutes() {
        assertEquals(-11, TripAlternative.minutesLater(first, slow))
        assertEquals(11, TripAlternative.minutesLater(slow, first))
        assertEquals(0, TripAlternative.minutesLater(slow, slow))
    }
}
