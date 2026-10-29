package io.github.elija7580.publit

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.LineColors
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.ModeGroup
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.StopGrouping
import io.github.elija7580.publit.data.StopTime
import io.github.elija7580.publit.data.StopTimesResponse
import io.github.elija7580.publit.data.TransitousClient
import io.github.elija7580.publit.data.TripProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

class CoreLogicTest {
    private val now = Instant.parse("2026-10-29T18:00:00Z")

    private fun st(name: String, stopId: String, lat: Double, lon: Double, mode: String, minutes: Long, headsign: String = "Ziel") =
        StopTime(
            place = Place(name = name, stopId = stopId, lat = lat, lon = lon, departure = now.plusSeconds(minutes * 60)),
            mode = mode, headsign = headsign, tripId = "$stopId-$minutes-$mode",
        )

    @Test fun platformsOfOneStationAreGrouped() {
        val list = listOf(
            st("Wien Hbf", "a", 48.1850, 16.3760, "RAIL", 5),
            st("Wien Hbf", "b", 48.1855, 16.3765, "SUBURBAN", 3),
            st("Südtiroler Platz", "c", 48.1870, 16.3740, "SUBWAY", 2),
        )
        val groups = StopGrouping.group(list, LatLon(48.1851, 16.3761))
        assertEquals(2, groups.size)
        assertEquals("Wien Hbf", groups[0].name)
        assertEquals(2, groups[0].departures.size)
        // sorted by time inside a station
        assertEquals("SUBURBAN", groups[0].departures[0].mode)
    }

    @Test fun streetStopsOnBothSidesStayApart() {
        val list = listOf(
            st("Kirche", "n", 48.0, 16.0, "BUS", 2, "Nord"),
            st("Kirche", "s", 48.0003, 16.0, "BUS", 4, "Süd"),
        )
        val groups = StopGrouping.group(list, LatLon(48.0, 16.0))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it.isPole })
        assertEquals(listOf("Nord"), groups.first { it.id == "n" }.directions)
    }

    @Test fun normalizedNameFoldsAccentsAndCase() {
        assertEquals("sudtiroler platz", StopGrouping.normalizedName(" Südtiroler Platz "))
        assertEquals("istanbul", StopGrouping.normalizedName("İstanbul"))
    }

    @Test fun modeFilterChips() {
        var f = ModeFilter()
        assertFalse(f.isActive)
        assertNull(f.apiValue)
        f = f.toggleOnly(ModeGroup.TRAM)
        assertEquals("TRAM", f.apiValue)
        assertTrue(f.allows("TRAM")); assertFalse(f.allows("BUS")); assertTrue(f.allows("WALK"))
        f = f.toggleOnly(ModeGroup.TRAM)
        assertFalse(f.isActive)
        assertEquals(setOf(ModeGroup.BUS, ModeGroup.TRAIN), ModeFilter.fromStorage("train,bus,nonsense").included)
    }

    @Test fun lineColorsKeepLegibleText() {
        assertEquals(LineColors.Pair("E3000F", "FFFFFF"), LineColors.resolve("e3000f", null, "000000"))
        // operator text with too little contrast is replaced
        assertEquals("000000", LineColors.resolve("FFDD00", "FFFFFF", "000000").text)
        assertEquals("2973CC", LineColors.resolve(null, null, "2973CC").background)
        assertTrue(LineColors.blendsIn("2D2B29", dark = true))
    }

    @Test fun l10nFormatsPluralsAndPlaceholders() {
        assertEquals("%d min", L10n.javaFormat("%lld min"))
        assertEquals("%1\$s to %2\$s", L10n.javaFormat("%1\$@ to %2\$@"))
        L10n.install(mapOf("%lld min" to "%d Min.", "%lld stops" to mapOf("one" to "%d Halt", "other" to "%d Halte")), Locale.GERMAN) {
            if (it == 1L) "one" else "other"
        }
        assertEquals("5 Min.", L10n.f("%lld min", 5))
        assertEquals("1 Halt", L10n.f("%lld stops", 1))
        assertEquals("3 Halte", L10n.f("%lld stops", 3))
        assertEquals("Unknown 2", L10n.f("Unknown %lld", 2))
        L10n.install(emptyMap(), Locale.ROOT) { "other" }
    }

    @Test fun decodesMotisStopTimes() {
        val body = """{"stopTimes":[{"place":{"name":"Karlsplatz","stopId":"x","lat":48.2,"lon":16.37,
            "departure":"2026-10-29T18:05:00Z","scheduledDeparture":"2026-10-29T18:03:00.000Z","tz":"Europe/Vienna"},
            "mode":"SUBWAY","realTime":true,"headsign":"Leopoldau","tripId":"t1","routeShortName":"U1","unknownField":1}],
            "nextPageCursor":"c"}"""
        val res = TransitousClient().json.decodeFromString(StopTimesResponse.serializer(), body)
        val s = res.stopTimes.single()
        assertEquals(2, s.delayMinutes)
        assertEquals("U1", s.lineName)
        assertEquals(1, StopTime.upcoming(res.stopTimes, now).size)
        assertEquals(0, StopTime.upcoming(res.stopTimes, now.plusSeconds(3600)).size)
    }

    @Test fun latLonApiValue() {
        assertEquals("48.13715,11.5", LatLon(48.137154, 11.5).apiValue)
        assertEquals(LatLon(48.1, 16.2), LatLon.fromApi("48.1,16.2"))
    }

    @Test fun tripProgress() {
        fun p(n: String, a: Long?, d: Long?) = Place(n, lat = 48.0, lon = a?.let { 11.0 + it / 1000.0 } ?: 11.0,
            arrival = a?.let { Instant.ofEpochSecond(it) }, departure = d?.let { Instant.ofEpochSecond(it) })
        val stops = listOf(p("A", null, 100), p("B", 200, 220), p("C", 300, null))
        assertEquals(TripProgress.NotStarted, TripProgress.at(Instant.ofEpochSecond(50), stops))
        assertEquals(TripProgress.Between(0, 0.5), TripProgress.at(Instant.ofEpochSecond(150), stops))
        assertEquals(TripProgress.AtStop(1), TripProgress.at(Instant.ofEpochSecond(210), stops))
        assertEquals(TripProgress.Finished, TripProgress.at(Instant.ofEpochSecond(300), stops))
    }

    @Test fun polylineDecode() {
        val pts = io.github.elija7580.publit.core.Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6); assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6)
    }

    @Test fun liveMatching() {
        assertEquals("404", io.github.elija7580.publit.live.LiveMatch.key("Bus 404"))
        assertEquals("s4", io.github.elija7580.publit.live.LiveMatch.key("S 4 (Zug-Nr. 5197)"))
        assertTrue(io.github.elija7580.publit.live.LiveMatch.sameLine(listOf("REX 7 2345"), listOf("REX 2345")))
        assertEquals("60201198", io.github.elija7580.publit.live.WienerLinienSource.diva("at:49:1198"))
        assertEquals("de:05113:9289", io.github.elija7580.publit.live.dhid("de:05113:9289_G"))
    }
}
