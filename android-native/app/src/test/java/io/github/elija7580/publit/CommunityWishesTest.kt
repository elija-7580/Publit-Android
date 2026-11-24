// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.PlaceRole
import io.github.elija7580.publit.data.SavedConnection
import io.github.elija7580.publit.data.SavedLine
import io.github.elija7580.publit.data.StopGroup
import io.github.elija7580.publit.tickets.TicketPlan
import io.github.elija7580.publit.widget.NearbyWidget
import java.time.ZoneId
import io.github.elija7580.publit.data.SavedPlace
import io.github.elija7580.publit.tickets.TicketPass
import io.github.elija7580.publit.tickets.WalletPassImport
import io.github.elija7580.publit.widget.ShortcutsWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Community wishes from iOS build 100: saved connections (#28), shortcuts widget (#6), Wallet passes (#20). */
class CommunityWishesTest {
    private val wien = LatLon(48.1855, 16.3757)
    private val graz = LatLon(47.0727, 15.4173)

    @Test fun connectionToggleAndWayBack() {
        val c = SavedConnection.of("Wien Hbf", wien, "Graz Hbf", graz)
        val saved = SavedConnection.toggled(emptyList(), c)
        assertEquals(listOf(c), saved)
        // A few metres off is the same connection.
        val near = SavedConnection.of("Wien Hauptbahnhof", LatLon(wien.lat + 0.00001, wien.lon), "Graz", graz)
        assertTrue(SavedConnection.toggled(saved, near).isEmpty())
        val back = c.reversed
        assertEquals("Graz Hbf", back.fromName)
        assertEquals(c.id.split(">").reversed().joinToString(">"), back.id)
        assertEquals(2, SavedConnection.toggled(saved, back).size)
    }

    @Test fun widgetTilesHomeWorkThenPlaces() {
        val items = listOf(
            SavedPlace("s", "Karlsplatz", null, 48.2, 16.37, stopId = "s"),
            SavedPlace("p1", "Café Prückel", null, 48.2, 16.38),
            SavedPlace("w", "Office", null, 48.21, 16.36, role = PlaceRole.WORK),
            SavedPlace("h", "Flat", null, 48.19, 16.35, role = PlaceRole.HOME),
            SavedPlace("p2", "Gym", null, 48.22, 16.39), SavedPlace("p3", "Park", null, 48.23, 16.4),
        )
        val tiles = ShortcutsWidget.tiles(items)
        assertEquals(4, tiles.size)
        assertEquals(listOf("publit://go/home", "publit://go/work"), tiles.take(2).map { it.link })
        assertEquals("Flat", tiles[0].detail)
        assertTrue(tiles[2].link.startsWith("publit://place?name=Caf%C3%A9%20Pr%C3%BCckel&lat=48.2&lon=16.38"))
        assertTrue(ShortcutsWidget.tiles(emptyList()).isEmpty())
    }

    private fun pkpass(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write("{}".toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("pass.json")); z.write(json.toByteArray()); z.closeEntry()
        }
        return out.toByteArray()
    }

    @Test fun readsATrainBoardingPass() {
        val json = """{"formatVersion":1,"organizationName":"Westbahn","description":"Ticket Wien – Salzburg","serialNumber":"WB123",
            "backgroundColor":"rgb(0, 120, 80)","expirationDate":"2026-12-02T23:59:00+01:00","relevantDate":"2026-12-01T08:00:00+01:00",
            "barcodes":[{"format":"PKBarcodeFormatAztec","message":"ABC123","messageEncoding":"iso-8859-1"}],
            "boardingPass":{"transitType":"PKTransitTypeTrain",
              "primaryFields":[{"key":"from","label":"Wien Westbahnhof","value":"WIE"},{"key":"to","label":"To","value":"Salzburg Hbf"}],
              "auxiliaryFields":[{"key":"passenger","label":"Passenger","value":"Alex Muster"},{"key":"class","label":"Class","value":"2"}],
              "backFields":[{"key":"terms","label":"Terms","value":"Valid on the day."}]}}"""
        val bytes = pkpass(json)
        assertTrue(WalletPassImport.isZip(bytes))
        val r = WalletPassImport.read(bytes)
        val p = r.pass
        assertEquals("Wien Westbahnhof", p.origin)
        assertEquals("Salzburg Hbf", p.destination)
        assertEquals(TicketPass.Symbol.TRAIN, p.symbol)
        assertEquals("Alex Muster", p.passenger)
        assertEquals("2", p.travelClass)
        assertEquals(TicketPass.Barcode.Format.AZTEC, p.barcode?.format)
        assertEquals("ABC123", p.barcode?.message)
        assertEquals(Instant.parse("2026-12-02T22:59:00Z"), p.validUntil)
        assertEquals("007850", r.color)
        assertEquals("Terms: Valid on the day.", p.notes)
        assertEquals(TicketPass.Category.SINGLE, p.category)
    }

    @Test fun binaryCodeKeepsItsBytes() {
        // UIC 918.3 travels as ISO-8859-1 text: one character per byte.
        val json = """{"organizationName":"ÖBB","barcode":{"format":"PKBarcodeFormatAztec","message":"#UT01\u0001ÿ","messageEncoding":"iso-8859-1"},"generic":{}}"""
        val code = WalletPassImport.readJson(json.toByteArray()).codes.single()
        assertNull(code.message)
        val raw = java.util.Base64.getDecoder().decode(code.payload)
        assertEquals(listOf(0x23, 0x55, 0x54, 0x30, 0x31, 0x01, 0xFF), raw.map { it.toInt() and 0xFF })
    }

    @Test fun notAPass() {
        val failed = runCatching { WalletPassImport.read(pkpass("{}").copyOf(10)) }
        assertTrue(failed.exceptionOrNull() is WalletPassImport.NotAPass)
        assertTrue(runCatching { WalletPassImport.readJson("[1]".toByteArray()) }.exceptionOrNull() is WalletPassImport.NotAPass)
        assertEquals("0C2238", WalletPassImport.hex("#0c2238"))
        assertNull(WalletPassImport.hex("rgb(300, 0, 0)"))
    }

    @Test fun shortcutsAreSeparateFromPlaces() {
        val items = listOf(
            SavedPlace("h", "Flat", null, 48.19, 16.35, role = PlaceRole.HOME),
            SavedPlace("g", "Gym", null, 48.22, 16.39, shortcut = true, symbol = "fitness"),
            SavedPlace("p", "Park", null, 48.23, 16.4),
        )
        val state = io.github.elija7580.publit.data.FavoritesStore.State(items)
        assertEquals(listOf("g"), state.shortcuts.map { it.id })
        assertEquals(listOf("p"), state.places.map { it.id })
        assertEquals(listOf("h", "g"), state.pinned.map { it.id })
        assertEquals("Home", state.pinned[0].keyTitle)
        // Widget: Home, then the shortcut before the other place.
        assertEquals(listOf("publit://go/home", "★", "●"), ShortcutsWidget.tiles(items).let { listOf(it[0].link, it[1].symbol, it[2].symbol) })
    }

    @Test fun lineWithDirectionHasItsOwnId() {
        val both = SavedLine("r1", "SUBWAY", "U6", null, null, null, "s1", "Westbahnhof", 48.19, 16.33)
        val one = both.copy(directionId = "Floridsdorf", headsign = "Floridsdorf")
        assertEquals("r1@s1", both.id)
        assertEquals("r1@s1>Floridsdorf", one.id)
        assertEquals(both.id, one.anyDirection.id)
    }

    @Test fun nearbyFavouritesFirstOnlyWhenNear() {
        fun g(id: String, name: String, d: Double) = StopGroup(id, name, wien, listOf(id), emptyList(), d)
        val saved = listOf(SavedPlace("far", "Far", null, wien.lat, wien.lon, stopId = "far"), SavedPlace("b", "B", null, wien.lat, wien.lon, stopId = "b"))
        val order = NearbyWidget.ordered(listOf(g("a", "A", 50.0), g("b", "B", 400.0), g("far", "Far", 900.0)), saved)
        assertEquals(listOf("b", "a", "far"), order.map { it.first.id })
        assertEquals(listOf(true, false, false), order.map { it.second })
    }

    @Test fun ticketPlanTimes() {
        val zone = ZoneId.of("Europe/Vienna")
        val now = Instant.parse("2026-12-01T06:00:00Z")
        val timed = TicketPass(validFrom = Instant.parse("2026-12-01T08:30:00Z"), validUntil = Instant.parse("2026-12-01T22:59:00Z"))
        assertEquals(Instant.parse("2026-12-01T08:20:00Z"), TicketPlan.arriveBy(timed, now, zone))
        assertNull(TicketPlan.onwardFrom(timed, now, zone)) // 23:59 local is only "valid all day"
        val dayOnly = TicketPass(validFrom = Instant.parse("2026-11-30T23:00:00Z")) // 00:00 in Vienna
        assertNull(TicketPlan.departure(dayOnly, zone))
    }
}
