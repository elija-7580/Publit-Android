package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripGuide
import io.github.elija7580.publit.data.TripProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Your place on a ride from your own fix (iOS TripProgress.located / TripGuide.ridePosition, 7104c05). */
class RidePositionTest {
    private val t0 = Instant.parse("2026-11-16T08:00:00Z")
    // Four stops due east, about 1.1 km apart, two minutes each.
    private val stops = (0..3).map { i ->
        Place(name = "S$i", lat = 48.0, lon = 16.0 + i * 0.015, arrival = t0.plusSeconds(120L * i), departure = t0.plusSeconds(120L * i + 20))
    }
    private val leg = Leg(mode = "BUS", from = stops.first(), to = stops.last(), duration = 360, startTime = t0.plusSeconds(20),
        endTime = t0.plusSeconds(360), intermediateStops = stops.subList(1, 3))

    @Test fun fixOnTheLineBetweenStops() {
        val p = TripProgress.located(LatLon(48.0, 16.0225), stops, emptyList())
        assertTrue(p is TripProgress.Between && p.index == 1 && p.fraction in 0.4..0.6)
    }

    @Test fun fixNearAStopIsAtThatStop() = assertEquals(TripProgress.AtStop(2), TripProgress.located(LatLon(48.0, 16.0302), stops, emptyList()))

    @Test fun fixFarFromTheLineIsNotOnBoard() = assertNull(TripProgress.located(LatLon(48.01, 16.02), stops, emptyList()))

    @Test fun onBoardUsesTheFix() {
        val r = TripGuide.ridePosition(leg, t0.plusSeconds(150), LatLon(48.0, 16.0225))
        assertTrue(r.fromGps)
        assertTrue(r.progress is TripProgress.Between)
    }

    @Test fun withoutFixOrOutsideTheRideByTheClock() {
        assertFalse(TripGuide.ridePosition(leg, t0.plusSeconds(150), null).fromGps)
        assertFalse(TripGuide.ridePosition(leg, t0.plusSeconds(3600), LatLon(48.0, 16.0225)).fromGps)
    }
}
