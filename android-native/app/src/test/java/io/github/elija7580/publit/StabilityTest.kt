// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.DelayHistory
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.LastConnection
import io.github.elija7580.publit.data.LateTrains
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Edge cases behind crashes and stale data: a live answer for a replaced route, writes from two threads, odd tables, empty results. */
class StabilityTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private val p = Place(name = "P", lat = 48.2, lon = 16.37)
    private fun ride(id: String, startMin: Int = 10, delayMin: Int = 0, mode: String = "SUBWAY", scheduled: Boolean = true): Itinerary {
        val start = t0.plusSeconds((startMin + delayMin) * 60L)
        val leg = Leg(mode = mode, from = p, to = p, duration = 600, startTime = start, endTime = start.plusSeconds(600),
            scheduledStartTime = if (scheduled) t0.plusSeconds(startMin * 60L) else null, tripId = "T-$id")
        return Itinerary(id = id, duration = 600, startTime = leg.startTime, endTime = leg.endTime, legs = listOf(leg))
    }
    private fun store() = TripStore(Files.createTempDirectory("trips").toFile())

    @Test fun aLiveAnswerForAReplacedRouteIsDropped() {
        val s = store()
        val t = s.save(ride("old"), "A", "B")
        // The live refresh was asked for "old"; meanwhile the traveller switched to another connection.
        s.reroute(t.id, ride("new"), "C")
        assertFalse(s.updateIfCurrent(t.id, "old", ride("old", delayMin = 5)))
        assertEquals("new", s.trip(t.id)?.itinerary?.id)
        assertEquals("C", s.trip(t.id)?.fromName)
        // Still the route that was asked for: the fresh times are taken.
        assertTrue(s.updateIfCurrent(t.id, "new", ride("new", delayMin = 3)))
        assertEquals(t0.plusSeconds(13 * 60), s.trip(t.id)?.itinerary?.startTime)
        // A trip removed meanwhile does not come back.
        s.remove(t.id)
        assertFalse(s.updateIfCurrent(t.id, "new", ride("new")))
        assertTrue(s.trips.isEmpty())
    }

    @Test fun changesFromTwoThreadsAreAllKeptAndTheFileStaysReadable() {
        val dir = Files.createTempDirectory("trips").toFile()
        val s = TripStore(dir)
        val threads = (0 until 4).map { n ->
            Thread { for (i in 0 until 25) s.save(ride("r$n-$i", startMin = 10 + n * 100 + i), "A", "B") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(100, s.trips.size)
        // What is on disk is what a new launch reads.
        assertEquals(100, TripStore(dir).trips.size)
    }

    @Test fun aForecastTableStartingAtZeroMinutesGivesNumbers() {
        val table = DelayHistory.Table("H|SUBWAY|0|-1,0,1,2,3|50\nH|SUBWAY|0|-2,0,2,4,6|40\nH|SUBWAY|10|-3,0,3,6,9|30")
        for (m in listOf(-5.0, 0.0, 5.0, 10.0, 30.0)) {
            val (dist, n) = table.forecastError("SUBWAY", m)!!
            assertTrue("at $m min: ${dist.q}", dist.q.all { it.isFinite() })
            assertTrue(n > 0)
        }
        assertNull(table.forecastError("BUS", 5.0))
        assertNull(DelayHistory.Table().forecastError("SUBWAY", 5.0))
    }

    @Test fun lateTrainsWithNothingToGoOn() {
        assertTrue(LateTrains.stillReachable(emptyList(), t0, t0, emptySet()).isEmpty())
        // A walk only: no ride that could be late.
        val walk = ride("walk", mode = "WALK")
        assertFalse(LateTrains.isLate(walk))
        assertTrue(LateTrains.stillReachable(listOf(walk), t0, t0, emptySet()).isEmpty())
        // No timetable time beside the live one: not late, and not listed as a late train.
        val bare = ride("bare", startMin = 5, scheduled = false)
        assertFalse(LateTrains.isLate(bare))
        assertTrue(LateTrains.stillReachable(listOf(bare), t0, t0, emptySet()).isEmpty())
    }

    @Test fun aLateTrainOverMidnightIsStillOffered() {
        val vienna = ZoneId.of("Europe/Vienna")
        fun local(day: Int, h: Int, m: Int) = LocalDateTime.of(2026, 11, day, h, m).atZone(vienna).toInstant()
        // Due 23:55, 12 min late, searched at 00:02 the next day.
        val due = local(23, 23, 55)
        val start = due.plusSeconds(12 * 60)
        val leg = Leg(mode = "REGIONAL_RAIL", from = p, to = p, duration = 1800, startTime = start, endTime = start.plusSeconds(1800),
            scheduledStartTime = due, scheduledEndTime = due.plusSeconds(1800), realTime = true, tripId = "N1")
        val night = Itinerary(id = "night", duration = 1800, startTime = start, endTime = leg.endTime, legs = listOf(leg))
        val now = local(24, 0, 2)
        assertEquals(listOf("night"), LateTrains.stillReachable(listOf(night), now, now, emptySet()).map { it.id })
        // The service day of 00:02 ends at 04:00 the same morning; the train is the last one of it.
        assertEquals(local(24, 4, 0), LastConnection.deadline(now, vienna))
        assertEquals("night", LastConnection.last(LastConnection.upcoming(listOf(night), now))?.id)
        assertNull(LastConnection.last(emptyList()))
    }
}
