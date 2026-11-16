package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.ParkAndRide
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.PlanResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Home 10 km from two stations on different lines (feedback, Nov 2026); Gföhl must still use Krems. */
class ParkAndRideTest {
    private val t = Instant.parse("2026-11-16T07:00:00Z")
    private val p = Place(name = "P", lat = 48.0, lon = 15.0)
    private fun leg(mode: String, s: Int) = Leg(mode = mode, from = p, to = p, duration = s, startTime = t, endTime = t)
    private fun route(drive: Int, ride: Int = 3600) =
        Itinerary(id = "$drive", duration = drive + ride, startTime = t, endTime = t,
            legs = listOf(leg("CAR", drive), leg("WALK", 120), leg("REGIONAL_RAIL", ride)))

    @Test fun secondStationCompetesFarTownDoesNot() {
        val kept = ParkAndRide.nearStations(ParkAndRide.sensible(listOf(route(720), route(960), route(1500))), 720)
        assertEquals(listOf(720, 960), kept.map(ParkAndRide::driveSeconds))
    }

    @Test fun drivingMostOfTheWayIsDropped() {
        assertEquals(emptyList<Itinerary>(), ParkAndRide.sensible(listOf(route(480, 120))))
    }

    @Test fun widerLimit() {
        assertEquals(1320, ParkAndRide.widerLimit(listOf(route(720)), listOf(route(720))))
        assertNull(ParkAndRide.widerLimit(listOf(route(240)), listOf(route(240))))
        assertEquals(ParkAndRide.MAX_WIDER_DRIVE_SECONDS, ParkAndRide.widerLimit(emptyList(), emptyList()))
        assertNull(ParkAndRide.widerLimit(listOf(route(480, 120)), emptyList()))
    }

    @Test fun searchUsesSecondStation() = runBlocking {
        val r = ParkAndRide.search(ParkAndRide.MAX_DRIVE_SECONDS) { d ->
            PlanResponse(itineraries = if (d <= 900) listOf(route(720)) else listOf(route(720), route(960), route(1500)))
        }!!
        assertEquals(1320, r.drive)
        assertEquals(listOf(720, 960), r.routes.map(ParkAndRide::driveSeconds))
    }

    // Fallback (iOS): no stop within walking reach → Park & Ride before a two-hour walk.
    private val none = PlanResponse(itineraries = emptyList())
    private fun walkRoute() = Itinerary(id = "w", duration = 5000, startTime = t, endTime = t, legs = listOf(leg("WALK", 4000), leg("BUS", 600)))

    @Test fun parkAndRideBeforeATwoHourWalk() = runBlocking {
        val walks = mutableListOf<Int>()
        val f = ParkAndRide.walkingFirst(1800, userLimit = false,
            walk = { w -> walks += w; if (w >= 7200) PlanResponse(itineraries = listOf(walkRoute())) to listOf(walkRoute()) else none to emptyList() },
            car = { ParkAndRide.Result(PlanResponse(itineraries = listOf(route(720))), listOf(route(720)), 900, 720) })
        assertEquals(listOf(1800, 2700), walks)
        assertEquals(720, (f as ParkAndRide.Found.Car).result.nearest)
    }

    @Test fun noStationByCarThenTheLongWalk() = runBlocking {
        val f = ParkAndRide.walkingFirst(1800, userLimit = false,
            walk = { w -> if (w >= 7200) PlanResponse(itineraries = listOf(walkRoute())) to listOf(walkRoute()) else none to emptyList() },
            car = { null })
        assertEquals(7200, (f as ParkAndRide.Found.Walk).walk)
    }

    @Test fun foundOnFootNoCar() = runBlocking {
        var asked = false
        val f = ParkAndRide.walkingFirst(1800, userLimit = false, walk = { _ -> PlanResponse(itineraries = listOf(walkRoute())) to listOf(walkRoute()) },
            car = { asked = true; null })
        assertEquals(1800, (f as ParkAndRide.Found.Walk).walk)
        assertEquals(false, asked)
    }

    @Test fun ownWalkLimitNeverDrives() = runBlocking {
        var asked = false
        ParkAndRide.walkingFirst(900, userLimit = true, walk = { _ -> none to emptyList() }, car = { asked = true; null })
        assertEquals(false, asked)
    }
}
