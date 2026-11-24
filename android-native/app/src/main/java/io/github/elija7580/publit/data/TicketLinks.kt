// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.data

import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Where to buy a ticket for each operator of a route (TicketLinks.swift and ShopSearch.swift on iOS).
 * Order: a prefilled shop search (DB, FlixBus), the feed's booking link (Android, then web), the operator's
 * fare page, its website, a known shop. Shop links go through the shop's partner program when Publit has one.
 */
data class TicketLink(
    val operatorName: String,
    val url: String,
    val source: Source,
    /** The link goes through a partner program (Publit may get a commission). */
    val isPartner: Boolean = false,
    /** A shop search with start, destination and date; `url` is the shop's start page until it is resolved. */
    val search: ShopSearch? = null,
) {
    enum class Source { BOOKING, FARES, WEBSITE }

    val title: String
        get() = when (source) {
            Source.BOOKING -> L10n.f("Buy ticket: %@", operatorName)
            Source.FARES -> L10n.f("Fares and tickets: %@", operatorName)
            Source.WEBSITE -> L10n.f("%@ website", operatorName)
        }
}

object TicketLinks {
    fun operatorKey(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
            .split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /** Ticket shops of large operators whose feeds publish no URL (keys are operatorKey word sets). */
    val knownShops: Map<String, String> = mapOf(
        "u-bahn munchen" to "https://www.mvg.de/tickets-tarife.html",
        "mvg" to "https://www.mvg.de/tickets-tarife.html",
        "s-bahn munchen" to "https://www.mvv-muenchen.de/",
        "db" to "https://int.bahn.de",
        "deutsche bahn" to "https://int.bahn.de",
        "obb" to "https://tickets.oebb.at",
        "wiener linien" to "https://shop.wienerlinien.at",
        "trenitalia" to "https://www.trenitalia.com",
        "sbb" to "https://www.sbb.ch",
    )

    /** "DB" also applies to "DB Regio AG Bayern"; the most specific entry wins. */
    fun match(operatorName: String, table: Map<String, String>): String? {
        val words = operatorKey(operatorName).split(" ").toSet()
        return table.filterKeys { key -> key.split(" ").let { k -> k.isNotEmpty() && words.containsAll(k) } }
            .maxByOrNull { it.key.length }?.value
    }

    /** Only web pages: a custom scheme from feed data could otherwise start a call or another app. */
    fun webURL(s: String?): String? {
        val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val withScheme = if (t.contains("://")) t else "https://$t"
        return try {
            val u = URI(withScheme)
            if ((u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty()) withScheme else null
        } catch (_: Exception) { null }
    }

    private fun operatorName(leg: Leg): String = leg.agencyName?.trim()?.takeIf { it.isNotEmpty() } ?: leg.lineName

    /** `to`: where the user leaves this operator (the last stop of its last ride), for a search over several rides. */
    fun link(leg: Leg, to: Place? = null): TicketLink? {
        if (!leg.isTransit) return null
        val name = operatorName(leg)
        // A local network's own shop (e.g. MVV for "DB Regio AG S-Bahn München") beats a DB search.
        val local = match(name, knownShops)?.let { it != knownShops["db"] } ?: false
        if (!local) ShopSearch.of(name, leg.from, to ?: leg.to, leg.scheduledStartTime ?: leg.startTime)?.let { search ->
            return shop(name, search.dbURL ?: search.startPage, TicketLink.Source.BOOKING).copy(search = search)
        }
        (webURL(leg.ticketUrls?.android) ?: webURL(leg.ticketUrls?.web))?.let { return shop(name, it, TicketLink.Source.BOOKING) }
        webURL(leg.agencyFareUrl)?.let { return shop(name, it, TicketLink.Source.FARES) }
        webURL(leg.agencyUrl)?.let { return shop(name, it, TicketLink.Source.WEBSITE) }
        match(name, knownShops)?.let { return shop(name, it, TicketLink.Source.FARES) }
        return null
    }

    private fun shop(name: String, url: String, source: TicketLink.Source): TicketLink =
        PartnerLinks.wrap(url)?.let { TicketLink(name, it, source, isPartner = true) } ?: TicketLink(name, url, source)

    /** One link per operator, in the order the operators are used. */
    fun links(itinerary: Itinerary): List<TicketLink> {
        val legs = itinerary.transitLegs
        val seen = mutableSetOf<String>()
        return legs.mapNotNull { leg ->
            val key = operatorKey(operatorName(leg))
            if (key in seen) return@mapNotNull null
            val last = legs.lastOrNull { operatorKey(operatorName(it)) == key }
            link(leg, last?.to)?.also { seen += key }
        }
    }

    /** The link to open: for a shop search the finished search (through the partner program when there is one),
     *  else the link itself; the shop's start page when the search cannot be built. */
    suspend fun resolve(link: TicketLink): String {
        val search = link.search ?: return link.url
        if (search.shop == ShopSearch.Shop.DB) return link.url
        val url = search.resolve() ?: return link.url
        return PartnerLinks.wrap(url) ?: url
    }
}

/** A shop's affiliate program: `template` is its tracking link with `{url}`. Empty means off. */
data class PartnerProgram(val name: String, val hosts: List<String>, val template: String)

/** Partner links for ticket shops. Templates are filled in once a program has accepted Publit. */
object PartnerLinks {
    private val tlds = listOf("de", "com", "at", "ch", "fr", "it", "es", "nl", "pl", "se", "cz", "co.uk")
    val programs: List<PartnerProgram> = listOf(
        PartnerProgram("Deutsche Bahn", listOf("bahn.de", "bahn.com"), ""),
        PartnerProgram("FlixBus", tlds.map { "flixbus.$it" } + listOf("flixtrain.de", "flixtrain.com"), ""),
        PartnerProgram("Trainline", listOf("thetrainline.com", "trainline.eu", "trainline.de", "trainline.fr", "trainline.it", "trainline.es"), ""),
        PartnerProgram("Omio", tlds.map { "omio.$it" }, ""),
        PartnerProgram("SNCF Connect", listOf("sncf-connect.com"), ""),
        PartnerProgram("Eurostar", listOf("eurostar.com"), ""),
    )

    fun wrap(url: String, list: List<PartnerProgram> = programs): String? {
        val host = try { URI(url).host?.lowercase() } catch (_: Exception) { null } ?: return null
        val p = list.firstOrNull { p -> p.template.isNotEmpty() && p.hosts.any { host == it || host.endsWith(".$it") } } ?: return null
        val wrapped = p.template.replace("{url}", URLEncoder.encode(url, "UTF-8").replace("+", "%20"))
        return wrapped.takeIf { it.startsWith("https://") }
    }
}

/**
 * A shop search with start, destination and departure filled in, so the shop opens on the user's connections.
 * Checked by hand in November 2026: bahn.de (station names are enough) and the FlixBus shop (needs FlixBus city
 * IDs, looked up only when the user taps).
 */
data class ShopSearch(
    val shop: Shop,
    val fromName: String,
    val toName: String,
    val from: LatLon,
    val to: LatLon,
    val departure: Instant,
    val zone: ZoneId,
) {
    enum class Shop { DB, FLIX }

    val startPage: String
        get() = if (shop == Shop.DB) "https://www.bahn.de" else "https://www.flixbus.com"

    private fun format(pattern: String) = DateTimeFormatter.ofPattern(pattern).withZone(zone).format(departure)

    /** bahn.de connection search, one adult, 2nd class, connections from the departure time on. */
    val dbURL: String?
        get() {
            if (shop != Shop.DB) return null
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A")
            val fragment = listOf(
                "sts=true", "so=${enc(fromName)}", "zo=${enc(toName)}", "kl=2", "r=13:16:KLASSENLOS:1",
                "soid=${enc("A=1@O=$fromName@")}", "zoid=${enc("A=1@O=$toName@")}", "sot=ST", "zot=ST",
                "hd=${format("yyyy-MM-dd'T'HH:mm")}:00", "hza=D", "ar=false", "s=false", "d=false", "fm=false", "bp=false",
            ).joinToString("&")
            return "https://www.bahn.de/buchung/fahrplan/suche#$fragment"
        }

    fun flixURL(fromCity: String, toCity: String): String =
        "https://shop.flixbus.com/search?departureCity=${URLEncoder.encode(fromCity, "UTF-8")}" +
            "&arrivalCity=${URLEncoder.encode(toCity, "UTF-8")}&rideDate=${format("dd.MM.yyyy")}&adult=1"

    /** The finished search link; for FlixBus this looks up both cities (only the stop names go to FlixBus). */
    suspend fun resolve(): String? = when (shop) {
        Shop.DB -> dbURL
        Shop.FLIX -> coroutineScope {
            val a = async { FlixCities.id(from, fromName) }
            val b = async { FlixCities.id(to, toName) }
            val fromCity = a.await(); val toCity = b.await()
            if (fromCity != null && toCity != null && fromCity != toCity) flixURL(fromCity, toCity) else null
        }
    }

    companion object {
        fun shopFor(operatorName: String): Shop? {
            val key = TicketLinks.operatorKey(operatorName)
            val words = key.split(" ")
            if ("db" in words || key.contains("deutsche bahn")) return Shop.DB
            if (words.any { it.startsWith("flixbus") || it.startsWith("flixtrain") }) return Shop.FLIX
            return null
        }

        fun of(operatorName: String, from: Place, to: Place, departure: Instant): ShopSearch? {
            val shop = shopFor(operatorName) ?: return null
            if (from.name.isBlank() || to.name.isBlank()) return null
            val zone = from.tz?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Europe/Berlin")
            return ShopSearch(shop, from.name, to.name, from.coordinate, to.coordinate, departure, zone)
        }
    }
}

/** FlixBus city IDs, from the FlixBus shop's own city search (the one its web page uses). */
object FlixCities {
    @Serializable data class Location(val lat: Double, val lon: Double)
    @Serializable data class City(val id: String, val name: String, val location: Location)

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, String>()

    /** The stop name without its details ("Wien Erdberg (VIB)" → "Wien Erdberg"), then only the first word. */
    fun queries(name: String): List<String> {
        val base = name.split(Regex("[(,/–-]")).first().trim()
        val first = base.split(" ").firstOrNull().orEmpty()
        return (if (base == first) listOf(base) else listOf(base, first)).filter { it.isNotEmpty() }
    }

    /** The nearest city within 50 km of the stop. */
    fun nearest(cities: List<City>, to: LatLon): City? =
        cities.map { it to to.distanceTo(LatLon(it.location.lat, it.location.lon)) }
            .filter { it.second <= 50_000 }.minByOrNull { it.second }?.first

    fun decode(body: String): List<City> = json.decodeFromString(ListSerializer(City.serializer()), body)

    suspend fun id(near: LatLon, name: String): String? = withContext(Dispatchers.IO) {
        val key = "$name@${Math.round(near.lat * 100)},${Math.round(near.lon * 100)}"
        cache[key]?.let { return@withContext it }
        for (q in queries(name)) {
            val url = "https://global.api.flixbus.com/search/autocomplete/cities?q=" + URLEncoder.encode(q, "UTF-8") +
                "&lang=en&flixbus_cities_only=false"
            val body = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6_000; readTimeout = 6_000
                    setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
                    setRequestProperty("Accept", "application/json")
                }
                try { if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null } finally { conn.disconnect() }
            } catch (_: Exception) { null } ?: continue
            val city = runCatching { nearest(decode(body), near) }.getOrNull() ?: continue
            cache[key] = city.id
            return@withContext city.id
        }
        null
    }
}
