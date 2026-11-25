// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.SmartRouting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * How a change's chance reads in the route details (iOS `SmartChangesSection`, build 99): every change has a
 * percentage, estimates are marked "≈", and a ride that goes on in the same vehicle has none.
 */
class ChangePercentLabelTest {
    private val t0: Instant = Instant.parse("2026-11-25T18:00:00Z")
    private val stop: Place = Place(name = "P", lat = 48.2, lon = 16.37)
    private val ride: Leg = Leg(mode = "SUBWAY", from = stop, to = stop, duration = 600, startTime = t0, endTime = t0.plusSeconds(600))

    private fun change(chance: Double, source: SmartRouting.Change.Source): SmartRouting.Change =
        SmartRouting.Change(stopName = "P", incoming = ride, outgoing = ride, outgoingIndex = 1, walkSeconds = 120,
            spareMinutes = 4.0, chance = chance, source = source)

    @Test fun measuredChanceIsAPlainPercentage() {
        assertEquals("85 %", change(0.85, SmartRouting.Change.Source.History(240)).percentLabel)
    }

    @Test fun estimateIsMarked() {
        assertEquals("≈ 85 %", change(0.85, SmartRouting.Change.Source.Estimate).percentLabel)
        // An estimate is the default source.
        val plain: SmartRouting.Change = SmartRouting.Change(stopName = "P", incoming = ride, outgoing = ride, outgoingIndex = 1,
            walkSeconds = 120, spareMinutes = 4.0, chance = 0.6)
        assertEquals("≈ 60 %", plain.percentLabel)
    }

    @Test fun neverCertainNeverZero() {
        assertEquals("99 %", change(1.0, SmartRouting.Change.Source.History(900)).percentLabel)
        assertEquals("≈ 1 %", change(0.0, SmartRouting.Change.Source.Estimate).percentLabel)
    }

    @Test fun sameVehicleGoingOnHasNoPercentage() {
        assertNull(change(0.99, SmartRouting.Change.Source.ThroughRun).percentLabel)
    }
}
