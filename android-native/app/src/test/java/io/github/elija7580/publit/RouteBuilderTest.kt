// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RouteBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Same cases as iOS RouteBuilderTests (#142, #143): stops on a line, 0.01° (about 1.1 km) apart. */
class RouteBuilderTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun time(m: Int) = t0.plusSeconds(m * 60L)
    private fun place(n: Int) = Place(name = "S$n", stopId = "s$n", lat = 48 + n * 0.01, lon = 16.0)
    private fun leg(mode: String, from: Int, to: Int, start: Int, end: Int, trip: String? = null) =
        Leg(mode = mode, from = place(from), to = place(to), duration = (end - start) * 60, startTime = time(start), endTime = time(end),
            tripId = if (mode == "WALK") null else trip ?: "$mode-$from-$start")
    private fun route(id: String, legs: List<Leg>) =
        Itinerary(id = id, duration = (legs.last().endTime.epochSecond - legs.first().startTime.epochSecond).toInt(),
            startTime = legs.first().startTime, endTime = legs.last().endTime, legs = legs)

    /** Walk to S1, bus to S2, tram to S3, walk to S4. */
    private val planned = route("A", listOf(leg("WALK", 0, 1, 0, 5), leg("BUS", 1, 2, 6, 16), leg("TRAM", 2, 3, 20, 30), leg("WALK", 3, 4, 30, 34)))

    @Test fun keptLegsAndANewRestBecomeOneRoute() {
        val b = route("B", listOf(leg("SUBWAY", 2, 4, 25, 33)))
        val built = RouteBuilder.splice(planned, 2, b)!!
        assertEquals(listOf("WALK", "BUS", "SUBWAY"), built.legs.map { it.mode })
        assertEquals(time(33), built.endTime)
        assertNull(RouteBuilder.splice(planned, 2, route("E", listOf(leg("SUBWAY", 2, 4, 15, 23)))))
        assertNull(RouteBuilder.splice(planned, 2, route("F", listOf(leg("SUBWAY", 3, 4, 25, 30)))))
    }

    @Test fun theChangeMovesToAnotherStopOfTheRide() {
        val sameRide = route("Y", listOf(leg("BUS", 1, 3, 6, 20, "BUS-1-6")))
        val other = route("X", listOf(leg("TRAM", 1, 3, 7, 19)))
        val way = RouteBuilder.wayToChange(planned, 1, "BUS-1-6", listOf(other, sameRide))!!
        assertEquals("Y", way.id)
        assertEquals("X", RouteBuilder.wayToChange(planned, 1, "gone", listOf(sameRide, other))?.id)
        val built = RouteBuilder.changeAt(planned, 1, way, route("Z", listOf(leg("WALK", 3, 4, 21, 25))))!!
        assertEquals(listOf("WALK", "BUS", "WALK"), built.legs.map { it.mode })
        assertEquals("S3", built.legs[1].to.name)
        assertNull(RouteBuilder.changeAt(planned, 1, way, route("E", listOf(leg("WALK", 3, 4, 18, 22)))))
        val late = route("L", listOf(leg("WALK", 3, 4, 22, 26)))
        assertEquals(1, RouteBuilder.choices(planned, 1, way, listOf(late, late)).size)
        assertNull(RouteBuilder.wayToChange(planned, 1, null, listOf(route("T", listOf(leg("BUS", 1, 3, 2, 12))))))
    }

    @Test fun theStopsToGetOffAreThoseAfterBoarding() {
        val ride = leg("BUS", 1, 3, 6, 20)
        assertEquals(listOf("S3"), RouteBuilder.exitStops(ride, null).map { it.name })
        val trip = leg("BUS", 0, 5, 0, 30).copy(intermediateStops = listOf(place(1), place(2), place(3), place(4)))
        assertEquals(listOf("S2", "S3", "S4", "S5"), RouteBuilder.exitStops(ride, trip).map { it.name })
        assertNotNull(RouteBuilder.restart(planned, 0))
    }
}
