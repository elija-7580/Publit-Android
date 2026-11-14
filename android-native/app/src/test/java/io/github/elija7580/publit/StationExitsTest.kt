package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.StationEntrance
import io.github.elija7580.publit.data.StationExits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exit numbers of underground stations from OpenStreetMap ("Take exit B2"). */
class StationExitsTest {
    private val path = listOf(LatLon(48.0, 11.0), LatLon(48.001, 11.0))
    private val near = StationEntrance(1, "A", LatLon(48.0005, 11.0))
    private val through = StationEntrance(2, "C", LatLon(48.0, 11.002))

    @Test fun entranceTheRoutePassesThroughWins() {
        assertEquals("C", StationExits.pick(listOf(near, through), path, LatLon(48.0005, 11.0), 55.0, setOf(2L))?.ref)
        assertEquals("A", StationExits.pick(listOf(near, through), path, LatLon(48.0005, 11.0), 55.0, emptySet())?.ref)
    }

    @Test fun withoutLevelsTheFirstEntranceNextToThePath() {
        val later = StationEntrance(3, "D", LatLon(48.0009, 11.0))
        assertEquals("A", StationExits.pick(listOf(later, near), path, null, null, emptySet())?.ref)
        assertNull(StationExits.pick(listOf(through), path, null, null, emptySet()))
    }

    @Test fun onlyShortRefsAreExitNumbers() {
        assertEquals("B2", StationExits.clean(" B2 "))
        assertNull(StationExits.clean("Ausgang Nord-West"))
        assertNull(StationExits.clean("A;B"))
    }
}
