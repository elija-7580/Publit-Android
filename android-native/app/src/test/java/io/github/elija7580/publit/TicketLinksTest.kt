// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.FlixCities
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.PartnerLinks
import io.github.elija7580.publit.data.PartnerProgram
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.ShopSearch
import io.github.elija7580.publit.data.TicketLink
import io.github.elija7580.publit.data.TicketLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.time.Instant

class TicketLinksTest {
    // 2026-11-25 07:00 UTC = 08:00 in Munich.
    private val departure = Instant.parse("2026-11-25T07:00:00Z")
    private fun place(name: String, lat: Double, lon: Double) = Place(name = name, lat = lat, lon = lon, tz = "Europe/Berlin")
    private fun leg(agency: String, from: Place, to: Place, mode: String = "RAIL", website: String? = null) =
        Leg(mode = mode, from = from, to = to, startTime = departure, endTime = departure.plusSeconds(3600),
            scheduledStartTime = departure, agencyName = agency, agencyUrl = website)

    private val munich = place("München Hbf", 48.14, 11.56)
    private val augsburg = place("Augsburg Hbf", 48.37, 10.89)
    private val nuremberg = place("Nürnberg Hbf", 49.45, 11.08)

    @Test fun operatorsWithASearchableShop() {
        assertEquals(ShopSearch.Shop.DB, ShopSearch.shopFor("DB Fernverkehr AG"))
        assertEquals(ShopSearch.Shop.DB, ShopSearch.shopFor("Deutsche Bahn"))
        assertEquals(ShopSearch.Shop.FLIX, ShopSearch.shopFor("FlixBus"))
        assertEquals(ShopSearch.Shop.FLIX, ShopSearch.shopFor("FlixTrain GmbH"))
        assertNull(ShopSearch.shopFor("ÖBB-Personenverkehr AG"))
    }

    @Test fun dbLinkIsASearchFromBoardingToLastAlighting() {
        val it = Itinerary(duration = 7200, startTime = departure, endTime = departure.plusSeconds(7200), legs = listOf(
            leg("DB Fernverkehr AG", munich, augsburg), leg("DB Fernverkehr AG", augsburg, nuremberg)))
        val links = TicketLinks.links(it)
        assertEquals(1, links.size)
        val url = links[0].url
        assertEquals(TicketLink.Source.BOOKING, links[0].source)
        assertTrue(url, url.startsWith("https://www.bahn.de/buchung/fahrplan/suche#sts=true&"))
        assertTrue(url, url.contains("so=M%C3%BCnchen%20Hbf"))
        assertTrue(url, url.contains("zo=N%C3%BCrnberg%20Hbf"))
        assertTrue(url, url.contains("soid=A%3D1%40O%3DM%C3%BCnchen%20Hbf%40"))
        assertTrue(url, url.contains("hd=2026-11-25T08:00:00"))
    }

    @Test fun localNetworkShopBeatsTheDBSearch() {
        val link = TicketLinks.link(leg("DB Regio AG S-Bahn München", munich, augsburg))
        assertEquals("https://www.mvv-muenchen.de/", link?.url)
        assertNull(link?.search)
    }

    @Test fun otherOperatorsKeepTheirOwnPages() {
        val wl = TicketLinks.link(leg("Wiener Linien", munich, augsburg, mode = "SUBWAY"))
        assertEquals("https://shop.wienerlinien.at", wl?.url)
        val own = TicketLinks.link(leg("Some Bus", munich, augsburg, mode = "BUS", website = "busco.example"))
        assertEquals("https://busco.example", own?.url)
        assertEquals(TicketLink.Source.WEBSITE, own?.source)
        assertNull(TicketLinks.webURL("tel:123"))
    }

    @Test fun flixSearchUsesCityIDsAndShopDate() {
        val link = TicketLinks.link(leg("FlixBus", place("Wien Erdberg (VIB)", 48.19, 16.41), place("Graz Hbf", 47.07, 15.42), mode = "COACH"))!!
        assertEquals("https://www.flixbus.com", link.url)
        val url = URI(link.search!!.flixURL("a", "b"))
        assertEquals("shop.flixbus.com", url.host)
        assertEquals("departureCity=a&arrivalCity=b&rideDate=25.11.2026&adult=1", url.rawQuery)
    }

    @Test fun flixCityIsTheNearestMatchWithin50Km() {
        assertEquals(listOf("Wien Erdberg", "Wien"), FlixCities.queries("Wien Erdberg (VIB)"))
        assertEquals(listOf("Graz"), FlixCities.queries("Graz"))
        val cities = FlixCities.decode("""
            [{"id":"far","name":"Wien","location":{"lat":52.5,"lon":13.4},"score":1},
             {"id":"vie","name":"Wien","location":{"lat":48.2,"lon":16.37}},
             {"id":"air","name":"Flughafen Wien","location":{"lat":48.11,"lon":16.56}}]
        """.trimIndent())
        assertEquals("vie", FlixCities.nearest(cities, LatLon(48.19, 16.41))?.id)
        assertNull(FlixCities.nearest(cities, LatLon(40.4, -3.7)))
    }

    @Test fun partnerLinksWrapOnlyActivePrograms() {
        val programs = listOf(PartnerProgram("DB", listOf("bahn.de"), "https://www.awin1.com/cread.php?awinmid=1&awinaffid=2&ued={url}"))
        val wrapped = PartnerLinks.wrap("https://int.bahn.de/en?x=1", programs)!!
        assertTrue(wrapped, wrapped.endsWith("ued=https%3A%2F%2Fint.bahn.de%2Fen%3Fx%3D1"))
        assertNull(PartnerLinks.wrap("https://tickets.oebb.at", programs))
        assertTrue(PartnerLinks.programs.all { it.template.isEmpty() })
    }
}
