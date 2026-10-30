package io.github.elija7580.publit.data

import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** "Find nearby" on the map (PlaceCategory on iOS): one tap shows every restaurant, pharmacy or charger in view. */
enum class PlaceCategory(val word: String, val colorHex: String, private val titleKey: String, val filters: List<String>) {
    RESTAURANT("restaurant", "FF9500", "Restaurants", listOf("[\"amenity\"=\"restaurant\"]")),
    CAFE("cafe", "A2845E", "Cafés", listOf("[\"amenity\"=\"cafe\"]")),
    BAKERY("bakery", "FF9500", "Bakeries", listOf("[\"shop\"=\"bakery\"]")),
    SUPERMARKET("foodmarket", "E6A700", "Supermarkets", listOf("[\"shop\"=\"supermarket\"]")),
    STORE("store", "E6A700", "Shops", listOf("[\"shop\"][\"shop\"!~\"^(supermarket|bakery|vacant)$\"][\"name\"]")),
    PHARMACY("pharmacy", "FF3B30", "Pharmacies", listOf("[\"amenity\"=\"pharmacy\"]")),
    HOSPITAL("hospital", "FF3B30", "Hospitals", listOf("[\"amenity\"=\"hospital\"]")),
    HOTEL("hotel", "AF52DE", "Hotels", listOf("[\"tourism\"=\"hotel\"]")),
    ATM("atm", "30B05A", "ATMs", listOf("[\"amenity\"=\"atm\"]", "[\"amenity\"=\"bank\"][\"atm\"=\"yes\"]")),
    PARKING("parking", "0A84FF", "Parking", listOf("[\"amenity\"=\"parking\"][\"access\"!~\"^(private|no)$\"]")),
    EV_CHARGER("evcharger", "34C759", "EV chargers", listOf("[\"amenity\"=\"charging_station\"]")),
    GAS_STATION("gasstation", "0A84FF", "Petrol stations", listOf("[\"amenity\"=\"fuel\"]")),
    RESTROOM("restroom", "8E8E93", "Toilets", listOf("[\"amenity\"=\"toilets\"]")),
    MUSEUM("museum", "FF2D55", "Museums", listOf("[\"tourism\"=\"museum\"]")),
    PARK("park", "34C759", "Parks", listOf("[\"leisure\"=\"park\"][\"name\"]")),
    NIGHTLIFE("nightlife", "AF52DE", "Bars", listOf("[\"amenity\"~\"^(bar|pub|nightclub)$\"]"));

    val title: String get() = L10n.t(titleKey)

    companion object {
        /** Typed words that mean a category ("Apotheken", "pharmacies"), so they find all of them around. */
        fun matching(text: String): PlaceCategory? {
            val t = text.trim().lowercase()
            if (t.length < 3) return null
            return entries.firstOrNull { c -> val ti = c.title.lowercase(); ti == t || c.word == t || (ti.startsWith(t) && t.length >= 5) }
        }
    }
}

/** A place of a category found on the map. */
data class CategoryPlace(val name: String, val at: LatLon, val street: String?, val category: PlaceCategory)

/**
 * The places of one kind from OpenStreetMap (Overpass API) for the visible area (OSMPlaces on Android/iOS).
 * Public instances can be overloaded: a bounded fallback instead of an empty result on one outage.
 */
object OSMPlaces {
    private val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        "https://overpass.private.coffee/api/interpreter")

    fun query(c: PlaceCategory, south: Double, west: Double, north: Double, east: Double): String {
        val box = String.format(java.util.Locale.ROOT, "(%.5f,%.5f,%.5f,%.5f)", south, west, north, east)
        return "[out:json][timeout:20];(" + c.filters.joinToString("") { "nwr$it$box;" } + ");out center 60;"
    }

    suspend fun places(center: LatLon, latSpan: Double, lonSpan: Double, c: PlaceCategory): List<CategoryPlace> = withContext(Dispatchers.IO) {
        val dLat = minOf(latSpan * 1.1, 0.25) / 2; val dLon = minOf(lonSpan * 1.1, 0.35) / 2
        val body = "data=" + URLEncoder.encode(query(c, center.lat - dLat, center.lon - dLon, center.lat + dLat, center.lon + dLon), "UTF-8")
        for (e in endpoints) {
            val text = runCatching {
                val conn = (URL(e).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 25_000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
                }
                try { conn.outputStream.use { it.write(body.toByteArray()) }; if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() } }
                finally { conn.disconnect() }
            }.getOrNull() ?: continue
            val els = runCatching { JSONObject(text).getJSONArray("elements") }.getOrNull() ?: continue
            return@withContext (0 until minOf(els.length(), 60)).mapNotNull { i ->
                val el = els.optJSONObject(i) ?: return@mapNotNull null
                val lat = if (el.has("lat")) el.optDouble("lat") else el.optJSONObject("center")?.optDouble("lat")
                val lon = if (el.has("lon")) el.optDouble("lon") else el.optJSONObject("center")?.optDouble("lon")
                if (lat == null || lon == null || lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val tags = el.optJSONObject("tags")
                val name = tags?.optString("name")?.takeIf { it.isNotEmpty() } ?: tags?.optString("brand")?.takeIf { it.isNotEmpty() }
                    ?: tags?.optString("operator")?.takeIf { it.isNotEmpty() } ?: c.title
                val street = tags?.optString("addr:street")?.takeIf { it.isNotEmpty() }?.let { s -> listOfNotNull(s, tags.optString("addr:housenumber").takeIf { it.isNotEmpty() }).joinToString(" ") }
                CategoryPlace(name, LatLon(lat, lon), street, c)
            }
        }
        // An outage is not a successful search with zero places.
        throw java.io.IOException("Overpass unavailable")
    }
}
