// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.CalendarAppointment
import io.github.elija7580.publit.data.CalendarTrips
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Calendar events as destinations (CalendarTripsTests.swift on iOS). */
class CalendarTripsTest {
    private val now = Instant.parse("2026-11-24T08:00:00Z")

    private fun event(id: String, inMinutes: Long, location: String = "Herrengasse 14, Wien", allDay: Boolean = false,
                      lat: Double? = null, lon: Double? = null) =
        CalendarAppointment(id = id, title = "Meeting $id", start = now.plusSeconds(inMinutes * 60),
            end = now.plusSeconds((inMinutes + 60) * 60), location = location, lat = lat, lon = lon, allDay = allDay)

    @Test fun soonestFirstWithinTheWindow() {
        val list = CalendarTrips.suggestions(listOf(
            event("later", 300), event("soon", 30), event("tooSoon", 2), event("allDay", 60, allDay = true),
            event("zoom", 90, location = "https://zoom.us/j/123"), event("farAway", 40 * 60),
        ), now)
        assertEquals(listOf("soon", "later"), list.map { it.id })
    }

    @Test fun alreadyThereIsLeftOut() {
        val list = CalendarTrips.suggestions(listOf(event("here", 60, location = "Café Central", lat = 48.21, lon = 16.37)),
            now, here = LatLon(48.2101, 16.3701))
        assertTrue(list.isEmpty())
    }

    @Test fun eventsWithoutAClearPlaceAreLeftOut() {
        val list = CalendarTrips.suggestions(listOf(
            event("meeting", 60, "Betriebsversammlung"),
            event("room", 61, "Raum 3"),
            event("roomCode", 61, "G1.E.S.20/21/22"),
            event("floor", 62, "2. Stock, Besprechungsraum"),
            event("street", 63, "Herrengasse 14"),
            event("postcode", 64, "Wiener Straße\n1010 Wien"),
            event("french", 65, "12 rue de Rivoli"),
            event("nameTown", 66, "Café Central, Wien"),
            event("pin", 67, "Café Central", lat = 48.21, lon = 16.37),
        ), now, limit = 10)
        assertEquals(listOf("street", "postcode", "french", "nameTown", "pin"), list.map { it.id })
    }

    @Test fun workHomeAndSavedNamesGoToTheSavedPlace() {
        val work = SavedPlace(id = "w", name = "Firma Huber", lat = 48.19, lon = 16.35, role = PlaceRole.WORK)
        val home = SavedPlace(id = "h", name = "Daheim", lat = 48.22, lon = 16.40, role = PlaceRole.HOME)
        val gym = SavedPlace(id = "g", name = "Fitinn Mitte", lat = 48.20, lon = 16.36)
        val list = CalendarTrips.suggestions(listOf(
            event("work", 60, "Büro"), event("home", 61, "Zuhause"), event("gym", 62, "fitinn mitte"), event("unknown", 63, "Kantine"),
        ), now, saved = listOf(work, home, gym), limit = 10)
        assertEquals(listOf("work", "home", "gym"), list.map { it.id })
        assertEquals(LatLon(48.19, 16.35), list[0].coordinate)
        assertEquals(LatLon(48.22, 16.40), list[1].coordinate)
        assertEquals(LatLon(48.20, 16.36), list[2].coordinate)
        // Without a saved Work place, "Büro" alone is no clear place.
        assertTrue(CalendarTrips.suggestions(listOf(event("work", 60, "Büro")), now).isEmpty())
    }
}
