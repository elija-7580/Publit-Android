// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.LiveBar
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.SavedTrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * The bar above the tabs (iOS `LiveTripBar`, build 99): hiding it holds for a trip that is not started;
 * a started trip always shows, so it can be ended from the bar.
 */
class LiveBarTest {
    private val t0: Instant = Instant.parse("2026-11-25T18:00:00Z")
    private val stop: Place = Place(name = "P", lat = 48.2, lon = 16.37)

    private fun saved(id: String): SavedTrip {
        val ride: Leg = Leg(mode = "SUBWAY", from = stop, to = stop, duration = 600, startTime = t0, endTime = t0.plusSeconds(600))
        val route: Itinerary = Itinerary(id = id, duration = 600, startTime = t0, endTime = t0.plusSeconds(600), legs = listOf(ride))
        return SavedTrip(id = id, itinerary = route, fromName = "A", toName = "B", savedAt = t0)
    }

    private val trip: SavedTrip = saved("one")

    @Test fun showsTheBarsTrip() {
        assertEquals(trip, LiveBar.shown(trip, activeId = null, hiddenId = ""))
        assertNull(LiveBar.shown(null, activeId = null, hiddenId = ""))
    }

    @Test fun hiddenByHandStaysHidden() {
        assertNull(LiveBar.shown(trip, activeId = null, hiddenId = "one"))
        // Another trip is started: this one is still hidden.
        assertNull(LiveBar.shown(trip, activeId = "other", hiddenId = "one"))
    }

    @Test fun hidingOneTripDoesNotHideTheNext() {
        val next: SavedTrip = saved("two")
        assertEquals(next, LiveBar.shown(next, activeId = null, hiddenId = "one"))
    }

    @Test fun aStartedTripAlwaysShows() {
        // Hidden before it was started: it shows again while it runs.
        assertEquals(trip, LiveBar.shown(trip, activeId = "one", hiddenId = "one"))
        assertEquals(trip, LiveBar.shown(trip, activeId = "one", hiddenId = ""))
    }

    @Test fun endedFromTheBarItIsGone() {
        // The X on a started trip hides the bar for this trip and ends the trip: nothing is started any more.
        assertNull(LiveBar.shown(trip, activeId = null, hiddenId = "one"))
    }
}
