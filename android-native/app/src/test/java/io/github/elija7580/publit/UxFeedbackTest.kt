package io.github.elija7580.publit

import io.github.elija7580.publit.core.Fmt
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.LineGrouping
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RouteSort
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.TransitLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** UX feedback from 2026-11-03: route order, next-day marking, ÖBB line names. */
class UxFeedbackTest {
    private val rex4 = "at-Railway-Current-Reference-Data-2026_2-RW4-j26-140.TA.2-RW4-j26-1.15.R"
    private val r44 = "at-Railway-Current-Reference-Data-2026_3-R44-N-j26-1219.TA.3-R44-N-j26-1.47.H"

    @Test fun austrianRegionalTrainsShowTheirLine() {
        assertEquals("REX 4", TransitLabels.railLine(rex4, "REX 2855"))
        assertEquals("R 44", TransitLabels.railLine(r44, "R 6050"))
        assertEquals("REX 44", TransitLabels.railLine("at-Railway-Current-Reference-Data-2026_2-R44-N-j26-120.TA.2-R44-N-j26-1.19.H", "REX 6298"))
        assertEquals("CJX 5", TransitLabels.railLine("at-Railway-Current-Reference-Data-2026_4-CJ5-j26-1", "CJX 1904"))
        // Already a line, long distance, other feeds: unchanged.
        assertNull(TransitLabels.railLine("at-Railway-Current-Reference-Data-2026_1-S40-j26-1", "S40"))
        assertNull(TransitLabels.railLine("at-Railway-Current-Reference-Data-2026_10-A1-j26-1", "RJX 769"))
        assertNull(TransitLabels.railLine("at-PTA-Eastern-Region-Flex-2026_at:vor:446:", "446"))
        assertNull(TransitLabels.railLine(null, "REX 2855"))
    }

    @Test fun oneLineKeyForAllTrainsOfALine() {
        val other = "at-Railway-Current-Reference-Data-2026_2-RW4-j26-1107.TA.2-RW4-j26-1.39.R"
        assertEquals(TransitLabels.lineKey(rex4), TransitLabels.lineKey(other))
        assertEquals("REX 4", TransitLabels.railLine(TransitLabels.lineKey(rex4), "REX 2811"))
        assertEquals("at-PTA-Eastern-Region-Flex-2026_at:vor:446:", TransitLabels.lineKey("at-PTA-Eastern-Region-Flex-2026_at:vor:446:"))

        val t = Instant.parse("2026-11-03T16:52:00Z")
        fun dep(route: String, short: String, tripId: String) = StopTime(Place(name = "Krems Bahnhof", lat = 48.4, lon = 15.6, departure = t),
            mode = "REGIONAL_RAIL", tripId = tripId, routeId = route, routeShortName = short, displayName = short)
        val a = dep(rex4, "REX 2855", "a"); val b = dep(other, "REX 2857", "b")
        assertEquals("REX 4", a.lineName)
        assertEquals("REX 2855", a.serviceDetail)
        assertEquals(1, LineGrouping.lines(listOf(a, b)).size)
    }

    private fun route(start: String, end: String) = Itinerary(id = start, duration = 0, startTime = Instant.parse(start), endTime = Instant.parse(end),
        legs = listOf(io.github.elija7580.publit.data.Leg(mode = "BUS", from = Place(name = "A", lat = 0.0, lon = 0.0),
            to = Place(name = "B", lat = 0.0, lon = 0.0), startTime = Instant.parse(start), endTime = Instant.parse(end), tripId = start)))

    @Test fun routesAreInTimeOrderForDepartAndArrive() {
        val list = listOf(route("2026-11-03T18:30:00Z", "2026-11-03T19:10:00Z"), route("2026-11-03T17:30:00Z", "2026-11-03T18:40:00Z"),
            route("2026-11-03T17:30:00Z", "2026-11-03T18:20:00Z"))
        for (arriveBy in listOf(false, true)) for (sort in listOf(RouteSort.RECOMMENDED, RouteSort.DEPARTURE)) {
            val out = sort.apply(list, arriveBy)
            assertEquals(listOf("2026-11-03T17:30:00Z|2026-11-03T18:20:00Z", "2026-11-03T17:30:00Z|2026-11-03T18:40:00Z", "2026-11-03T18:30:00Z|2026-11-03T19:10:00Z"),
                out.map { "${it.startTime}|${it.endTime}" })
        }
    }

    @Test fun nextDayIsNamed() {
        val now = Instant.parse("2026-11-03T21:00:00Z") // 22:00 in Vienna
        assertNull(Fmt.dayIfNotToday(Instant.parse("2026-11-03T22:30:00Z"), "Europe/Vienna", now))
        assertNotNull(Fmt.dayIfNotToday(Instant.parse("2026-11-03T23:10:00Z"), "Europe/Vienna", now)) // 00:10 tomorrow
    }
}
