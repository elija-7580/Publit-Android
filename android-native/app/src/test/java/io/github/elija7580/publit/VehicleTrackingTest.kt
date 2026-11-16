package io.github.elija7580.publit

import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripProgress
import io.github.elija7580.publit.data.VehiclePositionSource
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Track vehicle" (iOS #68): the position source is never called GPS; next stops start where the vehicle is. */
class VehicleTrackingTest {
    @Test fun sourceIsNeverCalledGps() {
        assertEquals(VehiclePositionSource.LIVE_TIMES, VehiclePositionSource.of(TripProgress.Between(1, 0.5), true))
        assertEquals(VehiclePositionSource.TIMETABLE, VehiclePositionSource.of(TripProgress.AtStop(2), false))
        assertEquals(VehiclePositionSource.UNAVAILABLE, VehiclePositionSource.of(TripProgress.NotStarted, true))
        assertEquals(VehiclePositionSource.UNAVAILABLE, VehiclePositionSource.of(TripProgress.Finished, true))
    }

    @Test fun upcomingStops() {
        val stops = listOf("A", "B", "C", "D").map { Place(name = it, lat = 48.0, lon = 16.0) }
        assertEquals(listOf("C", "D"), VehiclePositionSource.upcoming(TripProgress.Between(1, 0.3), stops).map { it.name })
        assertEquals(listOf("B", "C", "D"), VehiclePositionSource.upcoming(TripProgress.AtStop(1), stops).map { it.name })
        assertEquals(4, VehiclePositionSource.upcoming(TripProgress.NotStarted, stops).size)
        assertEquals(0, VehiclePositionSource.upcoming(TripProgress.Finished, stops).size)
    }
}
