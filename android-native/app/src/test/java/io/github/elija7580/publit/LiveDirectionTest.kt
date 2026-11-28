// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.live.LiveDeparture
import io.github.elija7580.publit.live.LiveMatch
import io.github.elija7580.publit.live.LiveRide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Live times from operators' station boards belong to the ride's own direction (iOS `StabilityAuditTests`,
 * "Live times of the right direction", Publit#206).
 */
class LiveDirectionTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun date(min: Double): Instant = t0.plusSeconds((min * 60).toLong())

    private fun ride(headsign: String?, lines: List<String> = listOf("U4")) =
        LiveRide(LatLon(48.2, 16.37), null, "Schwedenplatz", lines, headsign, date(10.0), "SUBWAY")

    /** Both directions of a line leave a station in the same minute: the ride gets its own train's delay. */
    @Test fun liveTimeComesFromTheRidesDirection() {
        val board = listOf(
            LiveDeparture(listOf("U4"), "HEILIGENSTADT", date(10.0), date(17.0)),
            LiveDeparture(listOf("U4"), "HÜTTELDORF", date(10.0), date(10.0)))
        assertEquals(date(10.0), LiveMatch.best(ride("Hütteldorf"), board)?.expected)
        assertEquals(date(17.0), LiveMatch.best(ride("Wien Heiligenstadt"), board)?.expected)
        assertEquals(date(10.0), LiveMatch.best(ride("Hütteldorf"), board.reversed())?.expected)
    }

    /** The other direction's cancellation is not this ride's. */
    @Test fun otherDirectionsCancellationDoesNotCancelTheRide() {
        val board = listOf(
            LiveDeparture(listOf("U4"), "Heiligenstadt", date(10.0), cancelled = true),
            LiveDeparture(listOf("U4"), "Hütteldorf", date(11.0), date(12.0)))
        val hit = LiveMatch.best(ride("Hütteldorf"), board)
        assertEquals(false, hit?.cancelled)
        assertEquals(date(12.0), hit?.expected)
        // Only the other direction is on the board: no live time rather than the wrong one.
        assertNull(LiveMatch.best(ride("Hütteldorf"), listOf(board[0], LiveDeparture(listOf("U4"), "Hütteldorf", date(30.0)))))
    }

    /** Unknown or unrecognisable directions change nothing: the nearest timetable time wins as before. */
    @Test fun unknownDirectionKeepsTheNearestDeparture() {
        val board = listOf(
            LiveDeparture(listOf("U4"), "Depot", date(10.5), date(12.0)),
            LiveDeparture(listOf("U4"), "Betriebsbahnhof", date(10.0), date(11.0)))
        assertEquals(date(11.0), LiveMatch.best(ride("Hütteldorf"), board)?.expected)
        assertEquals(date(11.0), LiveMatch.best(ride(null), board)?.expected)
        val unnamed = listOf(LiveDeparture(listOf("U4"), scheduled = date(10.0), expected = date(13.0)))
        assertEquals(date(13.0), LiveMatch.best(ride("Hütteldorf"), unnamed)?.expected)
    }

    /** How well a destination fits a headsign: same name, one inside the other, a telling word, nothing. */
    @Test fun directionScores() {
        assertEquals(3, LiveMatch.direction("Hütteldorf", "HÜTTELDORF"))
        assertEquals(3, LiveMatch.direction("Praterstraße", "Praterstrasse"))
        assertEquals(2, LiveMatch.direction("Wien Heiligenstadt", "Heiligenstadt"))
        assertEquals(1, LiveMatch.direction("Wien Hütteldorf Bahnhof", "Hütteldorf, Station"))
        assertEquals(0, LiveMatch.direction("Wien Westbahnhof", "Wien Hauptbahnhof"))
        assertEquals(0, LiveMatch.direction("Hütteldorf", null))
        assertEquals(0, LiveMatch.direction("Hütteldorf", " - "))
    }

    /** Numbers no clock shows are neither a countdown nor a delay. */
    @Test fun absurdCountdownsAndDelaysAreIgnored() {
        assertEquals(6, LiveMatch.countdownMinutes(6))
        assertEquals(0, LiveMatch.countdownMinutes(0))
        assertNull(LiveMatch.countdownMinutes(-3))
        assertNull(LiveMatch.countdownMinutes(1441))
        assertNull(LiveMatch.countdownMinutes(null))
        assertEquals(3, LiveMatch.delayMinutes(3))
        assertEquals(-5, LiveMatch.delayMinutes(-5))
        assertNull(LiveMatch.delayMinutes(Int.MAX_VALUE))
        assertNull(LiveMatch.delayMinutes(Int.MIN_VALUE))
        assertNull(LiveMatch.delayMinutes(-100_000))
        assertNull(LiveMatch.delayMinutes(null))
    }
}
