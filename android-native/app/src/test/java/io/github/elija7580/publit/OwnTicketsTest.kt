package io.github.elija7580.publit

import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.OwnTicket
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.RouteOptions
import io.github.elija7580.publit.data.StopArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Own tickets (OwnTicketTests on iOS): routes only use rides the tickets cover. Real Transitous stop IDs. */
class OwnTicketsTest {
    private val t0 = Instant.parse("2026-11-15T10:00:00Z")
    private fun leg(mode: String, name: String? = null, agency: String? = null, from: String? = null, to: String? = null) = Leg(
        mode = mode, from = Place(name = "A", stopId = from, lat = 48.1, lon = 11.5), to = Place(name = "B", stopId = to, lat = 48.2, lon = 11.6),
        startTime = t0, endTime = t0.plusSeconds(600), routeShortName = name, agencyName = agency)
    private fun trip(vararg legs: Leg) = Itinerary(id = "t", duration = 600, startTime = t0, endTime = t0.plusSeconds(600), legs = legs.toList())

    private val munich = "de-DELFI_de:09162:100:11:12"; private val berlin = "de-DELFI_de:11000:900003200:3:54"
    private val salzburg = "de-DELFI_at:45:50002"; private val salzburgAT = "at-Railway-Current-Reference-Data-2026_at:45:50002:0:8"
    private val wienHbf = "at-Railway-Current-Reference-Data-2026_at:49:1349:0:28"; private val krems = "at-Railway-Current-Reference-Data-2026_at:43:3993:0:11"
    private val wienU = "at-PTA-Eastern-Region-Flex-2026_at:49:491:0:5"; private val airport = "at-Railway-Current-Reference-Data-2026_at:43:5764:0:3"
    private val zurich = "ch-opentransportdataswiss26_ch:1:sloid:3000:8:15"; private val bern = "ch-opentransportdataswiss26_ch:1:sloid:7000:6:12"
    private val basel = "de-DELFI_ch:1:sloid:10"; private val freiburg = "de-DELFI_de:08311:6508:3:1"

    @Test fun stopArea() {
        assertEquals(StopArea("at", 45), StopArea.of(salzburg))
        assertEquals(StopArea("at", 43), StopArea.of(krems))
        assertEquals(StopArea("de"), StopArea.of(munich))
        assertEquals(StopArea("ch"), StopArea.of(zurich))
        assertEquals(StopArea("de"), StopArea.of("de-VBN_000000100063"))
        assertNull(StopArea.of(null)); assertNull(StopArea.of("12345"))
    }

    @Test fun deutschlandticket() {
        val dt = OwnTicket.DEUTSCHLANDTICKET
        assertTrue(dt.covers(leg("BUS", "54"))); assertTrue(dt.covers(leg("RAIL", "RB 33")))
        assertFalse(dt.covers(leg("HIGHSPEED_RAIL", "ICE 615"))); assertFalse(dt.covers(leg("RAIL", "IC 2013")))
        assertFalse(dt.covers(leg("RAIL", "1240", agency = "FlixTrain"))); assertFalse(dt.covers(leg("COACH", "N850")))
        assertTrue(dt.covers(leg("REGIONAL_RAIL", "RE5", from = munich, to = salzburg)))
        assertFalse(dt.covers(leg("REGIONAL_RAIL", "REX 2817", from = krems, to = wienHbf)))
    }

    @Test fun otherTickets() {
        assertTrue(OwnTicket.BAHNCARD100.covers(leg("HIGHSPEED_RAIL", "29", agency = "DB Fernverkehr AG", from = munich, to = berlin)))
        assertFalse(OwnTicket.BAHNCARD100.covers(leg("HIGHSPEED_RAIL", "ICE 374", from = basel, to = freiburg)))
        assertTrue(OwnTicket.KLIMATICKET.covers(leg("LONG_DISTANCE", "RJX 265", from = salzburgAT, to = wienHbf)))
        assertFalse(OwnTicket.KLIMATICKET.covers(leg("BUS", "CAT", agency = "City Air Terminal Betriebsgesellschaft m.b.H.", from = wienHbf, to = airport)))
        assertFalse(OwnTicket.KLIMATICKET.covers(leg("BUS", "VAB 4", from = wienU, to = airport)))
        val ride = leg("REGIONAL_RAIL", "REX 2817", from = krems, to = wienHbf)
        assertFalse(OwnTicket.VOR_REGION.covers(ride)); assertTrue(OwnTicket.VOR_METROPOLREGION.covers(ride))
        assertTrue(OwnTicket.WIEN_JAHRESKARTE.covers(leg("SUBWAY", "U4", from = wienU, to = wienU)))
        assertFalse(OwnTicket.KLIMATICKET_TIROL.covers(leg("BUS", "R")))
        assertTrue(OwnTicket.GENERALABONNEMENT.covers(leg("LONG_DISTANCE", "IC1", from = zurich, to = bern)))
        assertFalse(OwnTicket.GENERALABONNEMENT.covers(leg("HIGHSPEED_RAIL", "ICE 374", from = basel, to = freiburg)))
    }

    @Test fun routesAndSearch() {
        val it2 = trip(leg("REGIONAL_RAIL", "RE5", from = munich, to = salzburg), leg("LONG_DISTANCE", "RJX 265", from = salzburgAT, to = wienHbf))
        assertTrue(OwnTicket.covers(it2, setOf(OwnTicket.DEUTSCHLANDTICKET, OwnTicket.KLIMATICKET)))
        assertFalse(OwnTicket.covers(it2, setOf(OwnTicket.DEUTSCHLANDTICKET)))
        assertTrue(OwnTicket.covers(it2, emptySet()))
        val o = RouteOptions(tickets = listOf("deutschlandticket"))
        val modes = o.transitModesValue!!.split(",").toSet()
        assertTrue(modes.containsAll(listOf("REGIONAL_RAIL", "BUS", "SUBWAY")))
        assertTrue(modes.none { it in setOf("HIGHSPEED_RAIL", "LONG_DISTANCE", "NIGHT_RAIL", "COACH", "AIRPLANE") })
        assertTrue(RouteOptions(tickets = listOf("deutschlandticket", "klimaticket")).transitModesValue!!.contains("HIGHSPEED_RAIL"))
        assertNull(RouteOptions().transitModesValue)
        assertFalse(o.isDefault)
    }

    @Test fun recognise() {
        assertEquals(OwnTicket.DEUTSCHLANDTICKET, OwnTicket.recognise("Deutschland-Ticket Job"))
        assertEquals(OwnTicket.KLIMATICKET, OwnTicket.recognise("KlimaTicket Ö Classic"))
        assertEquals(OwnTicket.VOR_METROPOLREGION, OwnTicket.recognise("VOR KlimaTicket MetropolRegion"))
        assertEquals(OwnTicket.KLIMATICKET_OOE, OwnTicket.recognise("KlimaTicket OÖ"))
        assertEquals(OwnTicket.GENERALABONNEMENT, OwnTicket.recognise("GA 2. Klasse"))
        assertNull(OwnTicket.recognise("Sparschiene Wien–Graz")); assertNull(OwnTicket.recognise("Gate 4"))
    }
}
