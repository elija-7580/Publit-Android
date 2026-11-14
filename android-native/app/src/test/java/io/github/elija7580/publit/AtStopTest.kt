package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Standing at the stop: no "walk 1 min" before boarding (iOS AtStopTests). */
class AtStopTest {
    private val t0 = Instant.parse("2026-11-14T10:00:00Z")
    private val stop = Place(name = "Universität", lat = 48.2, lon = 16.37)

    private fun route(walkMeters: Double, here: Place): Itinerary {
        val walk = Leg(mode = "WALK", from = here, to = stop, duration = 60, startTime = t0, endTime = t0.plusSeconds(60), distance = walkMeters)
        val bus = Leg(mode = "BUS", from = stop, to = Place(name = "B", lat = 48.21, lon = 16.38), duration = 960,
            startTime = t0.plusSeconds(240), endTime = t0.plusSeconds(1200), routeShortName = "13A")
        return Itinerary(duration = 1200, startTime = t0, endTime = t0.plusSeconds(1200), legs = listOf(walk, bus))
    }

    @Test fun atTheStopTheRouteStartsWithTheRide() {
        val here = Place(name = "Here", lat = 48.20015, lon = 16.3701)
        val out = Reach.boardingHere(listOf(route(20.0, here)), LatLon(here.lat, here.lon)).first()
        assertEquals("BUS", out.legs.first().mode)
        assertEquals(t0.plusSeconds(240), out.startTime)
        assertEquals(960, out.duration)
    }

    @Test fun acrossTheRoadOrFurtherTheWalkStays() {
        val here = Place(name = "Here", lat = 48.20015, lon = 16.3701)
        assertEquals("WALK", Reach.boardingHere(listOf(route(90.0, here)), LatLon(here.lat, here.lon)).first().legs.first().mode)
        val far = Place(name = "Far", lat = 48.20135, lon = 16.37)
        assertEquals(2, Reach.boardingHere(listOf(route(40.0, far)), LatLon(far.lat, far.lon)).first().legs.size)
    }

    @Test fun radius() {
        assertTrue(Reach.isAtStop(0.0)); assertTrue(Reach.isAtStop(Reach.AT_STOP_METERS))
        assertFalse(Reach.isAtStop(Reach.AT_STOP_METERS + 1)); assertFalse(Reach.isAtStop(Double.NaN))
    }
}
