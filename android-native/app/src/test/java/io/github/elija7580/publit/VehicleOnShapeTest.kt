// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The estimated vehicle between two stops follows the ride's shape, not the straight line between them
 * (iOS build 99, `TripProgress.position(path:)`; same cases as iOS `testPositionFollowsTheShape`).
 */
class VehicleOnShapeTest {
    private val a: Place = Place(name = "A", lat = 48.0, lon = 16.0)
    private val b: Place = Place(name = "B", lat = 48.0, lon = 16.2)
    private val stops: List<Place> = listOf(a, b)
    /** A detour north: up, across, down. */
    private val detour: List<LatLon> = listOf(LatLon(48.0, 16.0), LatLon(48.1, 16.0), LatLon(48.1, 16.2), LatLon(48.0, 16.2))
    private val halfway: TripProgress = TripProgress.Between(0, 0.5)

    @Test fun followsTheShape() {
        val mid: LatLon? = TripProgress.position(halfway, stops, detour)
        assertNotNull(mid)
        assertEquals(48.1, mid!!.lat, 0.001)
        assertEquals(16.1, mid.lon, 0.01)
    }

    @Test fun straightLineWithoutAShape() {
        val mid: LatLon? = TripProgress.position(halfway, stops)
        assertNotNull(mid)
        assertEquals(48.0, mid!!.lat, 1e-9)
        assertEquals(16.1, mid.lon, 1e-9)
        // One point is no shape.
        assertEquals(mid, TripProgress.position(halfway, stops, listOf(LatLon(48.1, 16.1))))
    }

    @Test fun straightLineWhenAStopLiesFarFromTheShape() {
        // The shape of another road, 30 km to the north: neither stop lies on it.
        val elsewhere: List<LatLon> = listOf(LatLon(48.3, 16.0), LatLon(48.3, 16.2))
        val mid: LatLon? = TripProgress.position(halfway, stops, elsewhere)
        assertEquals(48.0, mid!!.lat, 1e-9)
    }

    @Test fun straightLineWhenTheShapeRunsTheOtherWay() {
        // The stops come in the opposite order along this shape: do not run backwards along it.
        val mid: LatLon? = TripProgress.position(halfway, stops, detour.reversed())
        assertEquals(48.0, mid!!.lat, 1e-9)
    }

    @Test fun stopsBetweenOtherStopsUseTheirPartOfTheShape() {
        val c: Place = Place(name = "C", lat = 48.0, lon = 16.4)
        val line: List<LatLon> = detour + listOf(LatLon(48.0, 16.4))
        // Second part is straight along the shape: halfway between B and C.
        val mid: LatLon? = TripProgress.position(TripProgress.Between(1, 0.5), listOf(a, b, c), line)
        assertEquals(48.0, mid!!.lat, 0.001)
        assertEquals(16.3, mid.lon, 0.01)
        // The ends of a part are its stops.
        val start: LatLon? = TripProgress.position(TripProgress.Between(0, 0.0), listOf(a, b, c), line)
        assertEquals(16.0, start!!.lon, 0.001)
        assertEquals(48.0, start.lat, 0.001)
    }

    @Test fun atAStopIsTheStop() {
        assertEquals(b.coordinate, TripProgress.position(TripProgress.AtStop(1), stops, detour))
    }

    @Test fun noPositionOutsideTheStops() {
        assertNull(TripProgress.position(TripProgress.AtStop(5), stops, detour))
        assertNull(TripProgress.position(TripProgress.Between(1, 0.5), stops, detour))
        assertNull(TripProgress.position(TripProgress.Between(0, 0.5), emptyList()))
        assertNull(TripProgress.position(TripProgress.Finished, stops, detour))
        assertNull(TripProgress.position(TripProgress.NotStarted, stops, detour))
    }
}
