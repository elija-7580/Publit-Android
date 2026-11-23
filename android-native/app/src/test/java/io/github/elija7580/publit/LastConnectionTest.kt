// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.LastConnection
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class LastConnectionTest {
    private val vienna = ZoneId.of("Europe/Vienna")
    private fun local(day: Int, h: Int, m: Int = 0): Instant = LocalDateTime.of(2026, 11, day, h, m).atZone(vienna).toInstant()
    private val p = Place(name = "P", lat = 48.0, lon = 16.0)
    private fun ride(id: String, start: Instant, end: Instant, cancelled: Boolean = false) =
        Itinerary(id = id, duration = (end.epochSecond - start.epochSecond).toInt(), startTime = start, endTime = end,
            legs = listOf(Leg(mode = "BUS", from = p, to = p, duration = 60, startTime = start, endTime = end, cancelled = cancelled)))

    @Test fun deadlineIsFourInTheMorningAfterTheDay() {
        assertEquals(local(24, 4), LastConnection.deadline(local(23, 18), vienna))
        assertEquals(local(24, 4), LastConnection.deadline(local(23, 4), vienna))
        assertEquals(local(24, 4), LastConnection.deadline(local(24, 1, 30), vienna))
    }

    @Test fun lastIsTheRouteThatLeavesLatest() {
        val early = ride("early", local(23, 22, 30), local(23, 23, 40))
        val night = ride("night", local(24, 0, 5), local(24, 2, 57))
        val gone = ride("gone", local(24, 0, 40), local(24, 3, 10), cancelled = true)
        assertEquals("night", LastConnection.last(listOf(night, gone, early))?.id)
    }

    @Test fun departedRoutesAreDropped() {
        val a = ride("a", local(23, 22), local(23, 23))
        val b = ride("b", local(23, 23, 30), local(24, 0, 30))
        val c = ride("c", local(23, 23), local(24, 1))
        assertEquals(listOf("c", "b"), LastConnection.upcoming(listOf(b, a, c), local(23, 22, 30)).map { it.id })
        assertTrue(LastConnection.upcoming(listOf(a), local(23, 22, 30)).isEmpty())
    }
}
