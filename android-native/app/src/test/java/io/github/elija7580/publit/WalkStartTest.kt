package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.TripStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** "Start walking navigation" only when the trip begins on foot within the hour (iOS #66). */
class WalkStartTest {
    private val t = Instant.parse("2026-11-16T08:00:00Z")
    private val p = Place(name = "P", lat = 48.0, lon = 16.0)
    private fun at(min: Long) = t.plusSeconds(min * 60)
    private fun leg(mode: String, s: Long, e: Long) = Leg(mode = mode, from = p, to = p, duration = ((e - s) * 60).toInt(), startTime = at(s), endTime = at(e))
    private fun trip(vararg legs: Leg) = Itinerary(id = "x", duration = 0, startTime = legs.first().startTime, endTime = legs.last().endTime, legs = legs.toList())

    @Test fun startsOnFootOnlyWhenTheWalkComesSoon() {
        val walkFirst = trip(leg("WALK", 0, 6), leg("TRAM", 8, 20))
        assertTrue(TripStatus.startsOnFoot(walkFirst, at(-30)))
        assertTrue("walk under way", TripStatus.startsOnFoot(walkFirst, at(3)))
        assertFalse("more than an hour ahead", TripStatus.startsOnFoot(walkFirst, at(-90)))
        assertFalse("walk already over", TripStatus.startsOnFoot(walkFirst, at(7)))
        assertFalse(TripStatus.startsOnFoot(trip(leg("TRAM", 0, 10), leg("WALK", 10, 15)), at(-5)))
    }
}
