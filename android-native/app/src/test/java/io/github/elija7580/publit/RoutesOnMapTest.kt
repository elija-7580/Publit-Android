// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RoutesOnMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** All routes on one map (iOS `CompareRoutesMapView`, build 99, Publit#194): pick, drawing order, frame. */
class RoutesOnMapTest {
    private val t0: Instant = Instant.parse("2026-11-26T08:00:00Z")
    private fun at(min: Int): Instant = t0.plusSeconds(min * 60L)
    private val home: Place = Place(name = "Home", lat = 48.14, lon = 11.56)
    private val mid: Place = Place(name = "Mid", lat = 48.16, lon = 11.57)
    private val office: Place = Place(name = "Office", lat = 48.15, lon = 11.60)

    private fun ride(id: String, from: Place, to: Place, start: Int, end: Int): Leg =
        Leg(mode = "BUS", from = from, to = to, duration = (end - start) * 60, startTime = at(start), endTime = at(end),
            scheduledStartTime = at(start), routeShortName = "52", headsign = "East", tripId = "t-$id")
    private fun route(id: String, vararg legs: Leg): Itinerary =
        Itinerary(id = id, duration = ((legs.last().endTime.epochSecond - legs.first().startTime.epochSecond)).toInt(),
            startTime = legs.first().startTime, endTime = legs.last().endTime, transfers = legs.size - 1, legs = legs.toList())

    private val direct: Itinerary = route("direct", ride("a", home, office, 0, 20))
    private val viaMid: Itinerary = route("via", ride("b", home, mid, 2, 10), ride("c", mid, office, 14, 24))
    private val later: Itinerary = route("later", ride("d", home, office, 30, 50))
    private val found: List<Itinerary> = listOf(direct, viaMid, later)

    @Test fun thePickedRouteIsDrawnStrong() {
        assertEquals(viaMid.routeKey, RoutesOnMap.current(found, viaMid.routeKey)?.routeKey)
    }

    @Test fun withoutAPickTheFirstRouteIsStrong() {
        assertEquals(direct.routeKey, RoutesOnMap.current(found, null)?.routeKey)
    }

    @Test fun aPickThatIsNoLongerListedFallsBackToTheFirstRoute() {
        assertEquals(direct.routeKey, RoutesOnMap.current(found, "gone")?.routeKey)
    }

    @Test fun noRoutesNoPick() {
        assertNull(RoutesOnMap.current(emptyList(), viaMid.routeKey))
    }

    @Test fun theStrongRouteIsDrawnLastAndTheOthersKeepTheirOrder() {
        assertEquals(listOf(direct, later, viaMid).map { it.routeKey }, RoutesOnMap.ordered(found, viaMid.routeKey).map { it.routeKey })
        assertEquals(listOf(viaMid, later, direct).map { it.routeKey }, RoutesOnMap.ordered(found, direct.routeKey).map { it.routeKey })
    }

    @Test fun everyRouteIsDrawnOnceWhateverIsPicked() {
        for (key in listOf(direct.routeKey, viaMid.routeKey, later.routeKey, "gone", null)) {
            val drawn = RoutesOnMap.ordered(found, key).map { it.routeKey }
            assertEquals(found.map { it.routeKey }.sorted(), drawn.sorted())
        }
    }

    @Test fun theFrameHoldsStartEndAndChangePointsOfAllRoutes() {
        val frame: List<LatLon> = RoutesOnMap.frame(found)
        for (place in listOf(home, mid, office)) assertTrue(place.name, frame.contains(place.coordinate))
    }

    @Test fun aRouteThatIsNotPickedStillCountsForTheFrame() {
        // Only the route via "Mid" goes there: the frame must not shrink to the strong route.
        assertTrue(RoutesOnMap.frame(listOf(direct, viaMid)).contains(mid.coordinate))
        assertTrue(RoutesOnMap.frame(listOf(direct)).none { it == mid.coordinate })
    }

    @Test fun noRoutesNoFrame() {
        assertTrue(RoutesOnMap.frame(emptyList()).isEmpty())
    }
}
