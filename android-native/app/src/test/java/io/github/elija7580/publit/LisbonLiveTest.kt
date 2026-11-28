// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.live.CarrisMetropolitanaSource
import io.github.elija7580.publit.live.LiveOverlay
import io.github.elija7580.publit.live.LiveRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Wish #4 "Real time transit for Lisbon Area": live times from Carris Metropolitana (iOS LisbonLiveTests). */
class LisbonLiveTest {
    private fun ride(stop: String?) = LiveRide(LatLon(38.768, -9.1), stop, "Gare do Oriente", listOf("4701"), "Lisboa (Oriente)",
        Instant.ofEpochSecond(1_795_863_600), "BUS")

    @Test fun coversOnlyCarrisMetropolitanaStops() {
        assertTrue(CarrisMetropolitanaSource.covers(ride("pt-Carris-Metropolitana_060001")))
        assertFalse(CarrisMetropolitanaSource.covers(ride("pt-Carris_7817")))
        assertFalse(CarrisMetropolitanaSource.covers(ride(null)))
        assertTrue(CarrisMetropolitanaSource.onlyWithoutLive)
        assertTrue("Carris Metropolitana" in LiveOverlay.sourceNames)
    }

    @Test fun stopIdsTryTheLeadingZeroSpelling() {
        assertEquals(listOf("160061"), CarrisMetropolitanaSource.stopIds("pt-Carris-Metropolitana_160061"))
        assertEquals(listOf("360322", "060322"), CarrisMetropolitanaSource.stopIds("pt-Carris-Metropolitana_360322"))
        assertEquals(emptyList<String>(), CarrisMetropolitanaSource.stopIds("pt-Carris-Metropolitana_abc"))
        assertEquals(emptyList<String>(), CarrisMetropolitanaSource.stopIds("pt-Carris_7817"))
    }

    @Test fun tripKeyAndLine() {
        assertEquals("[FBZQZ][A2L1N]4701_0_1|2800|1100",
            CarrisMetropolitanaSource.tripKey("20261128_11:00_pt-Carris-Metropolitana_[FBZQZ][A2L1N]4701_0_1|2800|1100"))
        assertNull(CarrisMetropolitanaSource.tripKey("20261128_11:00_pt-CP_123"))
        assertNull(CarrisMetropolitanaSource.tripKey(null))
        assertEquals("4701", CarrisMetropolitanaSource.line("[A2L1N]4701"))
        assertEquals("4701", CarrisMetropolitanaSource.line("4701"))
    }
}
