// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import io.github.elija7580.publit.data.EncodedPolyline
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.OnBoardPlan
import io.github.elija7580.publit.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Same cases as iOS OnBoardPlanTests (Publit#192): plan from the vehicle you ride (idea: BudapestGO, BKK). */
class OnBoardPlanTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun time(m: Int) = t0.plusSeconds(m * 60L)
    private fun place(n: Int, at: Int? = null) = Place(name = "S$n", stopId = "s$n", lat = 48 + n * 0.01, lon = 16.0,
        arrival = at?.let(::time), departure = at?.let(::time), scheduledArrival = at?.let(::time), scheduledDeparture = at?.let(::time))

    /** Tram T1 from S0 to S5, two minutes between stops. */
    private fun tram(): Leg {
        val shape = (0..50).map { LatLon(48 + it * 0.001, 16.0) }
        return Leg(mode = "TRAM", from = place(0, 0), to = place(5, 10), duration = 600, startTime = time(0), endTime = time(10),
            scheduledStartTime = time(0), scheduledEndTime = time(10), realTime = true, tripId = "T1", routeShortName = "1",
            intermediateStops = (1..4).map { place(it, it * 2) }, legGeometry = EncodedPolyline(Polyline.encode(shape, 6), 6, 51))
    }

    private fun leg(mode: String, from: Int, to: Int, start: Int, end: Int, trip: String? = null) =
        Leg(mode = mode, from = place(from), to = place(to), duration = (end - start) * 60, startTime = time(start), endTime = time(end),
            scheduledStartTime = time(start), scheduledEndTime = time(end), tripId = if (mode == "WALK") null else trip ?: "$mode-$from")

    private fun route(id: String, legs: List<Leg>) = Itinerary(id = id, duration = (legs.last().endTime.epochSecond - legs.first().startTime.epochSecond).toInt(),
        startTime = legs.first().startTime, endTime = legs.last().endTime, transfers = maxOf(0, legs.count { it.isTransit } - 1), legs = legs)

    @Test fun polylineRoundTrip() {
        assertEquals("_p~iF~ps|U", Polyline.encode(listOf(LatLon(38.5, -120.2)), 5))
        val pts = listOf(LatLon(47.49791, 19.04023), LatLon(-33.1, -70.6))
        val back = Polyline.decode(Polyline.encode(pts, 6), 6)
        assertEquals(2, back.size)
        assertEquals(47.49791, back[0].lat, 1e-6)
        assertEquals(-70.6, back[1].lon, 1e-6)
    }

    @Test fun positionBetweenStops() {
        val pos = OnBoardPlan.position(tram().allStops, time(3))!!
        assertEquals(1, pos.from); assertEquals(2, pos.next); assertEquals(time(4), pos.at)
        assertNull(OnBoardPlan.position(tram().allStops, time(11)))
    }

    @Test fun rideIsCutOut() {
        val r = OnBoardPlan.ride(tram(), 1, 4)!!
        assertEquals("S1", r.from.name); assertEquals("S4", r.to.name)
        assertEquals(listOf("S2", "S3"), r.intermediateStops!!.map { it.name })
        assertEquals(time(2), r.startTime); assertEquals(time(8), r.endTime)
        assertEquals(31, Polyline.decode(r.legGeometry!!.points, 6).size)
        assertNull(OnBoardPlan.ride(tram(), 3, 3))
    }

    @Test fun stayingOnIsOneRide() {
        val pos = OnBoardPlan.position(tram().allStops, time(3))!!
        val found = route("X", listOf(leg("TRAM", 2, 4, 4, 8, "T1"), leg("BUS", 4, 7, 10, 16)))
        val r = OnBoardPlan.route(found, tram(), pos)!!
        assertEquals(listOf("TRAM", "BUS"), r.legs.map { it.mode })
        assertEquals("S1", r.legs[0].from.name); assertEquals("S4", r.legs[0].to.name)
        assertEquals(1, r.transfers)
        assertEquals(OnBoardPlan.Ref("T1", 1, 4, "X", 1), OnBoardPlan.decodeId(r.id))
    }

    @Test fun gettingOffAtTheNextStop() {
        val pos = OnBoardPlan.position(tram().allStops, time(3))!!
        val found = route("Y", listOf(leg("WALK", 2, 9, 4, 6), leg("SUBWAY", 9, 12, 7, 12)))
        val r = OnBoardPlan.route(found, tram(), pos)!!
        assertEquals(listOf("TRAM", "WALK", "SUBWAY"), r.legs.map { it.mode })
        assertEquals("S2", r.legs[0].to.name)
        assertEquals(OnBoardPlan.Ref("T1", 1, 2, "Y", 0), OnBoardPlan.decodeId(r.id))
    }

    @Test fun sortedAndWithoutWorseChoices() {
        val pos = OnBoardPlan.position(tram().allStops, time(3))!!
        val stay = route("A", listOf(leg("TRAM", 2, 5, 4, 10, "T1"), leg("WALK", 5, 6, 10, 20)))
        val change = route("B", listOf(leg("BUS", 2, 6, 5, 15)))
        val wait = route("C", listOf(leg("TRAM", 2, 5, 9, 15, "T2"), leg("WALK", 5, 6, 15, 25)))
        val routes = OnBoardPlan.routes(listOf(stay, change, stay, wait), tram(), pos)
        assertEquals(listOf(time(15), time(20)), routes.map { it.endTime })
    }

    @Test fun refreshedFromTheTrip() {
        val ref = OnBoardPlan.Ref("T1", 1, 4, null, 0)
        val r = OnBoardPlan.refreshed(ref, route("T", listOf(tram())), null)
        assertNotNull(r)
        assertEquals(1, r!!.legs.size)
        assertEquals("S4", r.legs[0].to.name)
        assertNull(OnBoardPlan.decodeId("abc"))
    }
}
