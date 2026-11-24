// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Departures" on a stop of a trip (iOS Publit#130, TripView): which board the stop opens. */
class TripStopDeparturesTest {
    private fun place(stopId: String? = null, parentId: String? = null) =
        Place(name = "Karlsplatz", stopId = stopId, parentId = parentId, lat = 48.2, lon = 16.37)

    @Test fun aPlatformOpensItsStation() {
        assertEquals("at:49:1000", place(stopId = "at:49:1000:0:1", parentId = "at:49:1000").boardId)
    }

    @Test fun aStopWithoutStationOpensItself() {
        assertEquals("at:49:1000:0:1", place(stopId = "at:49:1000:0:1").boardId)
    }

    @Test fun aBarePointHasNoBoard() {
        assertNull(place().boardId)
        assertNull(place(stopId = " ").boardId)
    }

    @Test fun anEmptyStationFallsBackToTheStop() {
        assertEquals("at:49:1000:0:1", place(stopId = "at:49:1000:0:1", parentId = "").boardId)
    }
}
