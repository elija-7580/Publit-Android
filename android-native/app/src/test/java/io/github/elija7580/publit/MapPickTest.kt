package io.github.elija7580.publit

import io.github.elija7580.publit.data.GeocodeMatch
import io.github.elija7580.publit.data.MapPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Choose on map" (iOS #67): the pinned point is named after its street, else the nearest place. */
class MapPickTest {
    private fun m(type: String, name: String, street: String? = null, no: String? = null) =
        GeocodeMatch(type = type, name = name, lat = 48.2, lon = 16.37, street = street, houseNumber = no)

    @Test fun streetAndNumberOfTheAddress() =
        assertEquals("Kärntner Straße 5", MapPick.label(listOf(m("STOP", "Stephansplatz"), m("ADDRESS", "Kärntner Straße 5", "Kärntner Straße", "5"))))

    @Test fun placeNameWhenNoAddress() =
        assertEquals("Stadtpark", MapPick.label(listOf(m("STOP", "Stadtpark U"), m("PLACE", "Stadtpark"))))

    @Test fun addressWithoutStreetUsesItsName() = assertEquals("Hof 3", MapPick.label(listOf(m("ADDRESS", "Hof 3"))))

    @Test fun nothingKnown() = assertNull(MapPick.label(listOf(m("STOP", "Karlsplatz"))))

    @Test fun spanKeepsAMinimum() {
        assertEquals(150.0, MapPick.spanMeters(0.0001), 0.0)
        assertEquals(1110.0, MapPick.spanMeters(0.01), 0.01)
    }
}
