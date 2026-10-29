package io.github.elija7580.publit.data

import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class ApiException(message: String) : IOException(message)

/**
 * Transitous (MOTIS) API, as TransitousClient in the iOS app. Usage policy: open source,
 * non-commercial, identifying User-Agent, visible attribution.
 */
class TransitousClient(
    private val base: String = "https://api.transitous.org",
    private val cacheDir: File? = null,
) {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private class Entry(val url: String, val body: String, val at: Long)
    private val memory = ConcurrentHashMap<String, Entry>()

    /** Departures around a point; `modes` from ModeFilter.apiValue, null for all. */
    suspend fun departures(near: LatLon, radius: Int, count: Int, modes: String? = null): StopTimesResponse {
        val q = mutableListOf("center" to near.apiValue, "withAlerts" to "true", "radius" to radius.toString(), "n" to count.toString())
        modes?.let { q += "mode" to it }
        val body = get("/api/v6/stoptimes", q, maxAgeSeconds = 20)
        val res = json.decodeFromString(StopTimesResponse.serializer(), body)
        if (res.stopTimes.isNotEmpty()) saveLastNearby(body, near, modes)
        return res
    }

    /** Departures at a stop; `radius` also includes the other platforms of the station. */
    suspend fun departures(stopId: String, count: Int, radius: Int? = null, modes: String? = null): StopTimesResponse {
        val q = mutableListOf("stopId" to stopId, "n" to count.toString(), "withAlerts" to "true")
        radius?.let { q += "radius" to it.toString() }
        modes?.let { q += "mode" to it }
        return json.decodeFromString(StopTimesResponse.serializer(), get("/api/v6/stoptimes", q, maxAgeSeconds = 15))
    }

    /** Places and stops for a search text, nearby ones first. */
    suspend fun geocode(text: String, near: LatLon?, language: String): List<GeocodeMatch> {
        val q = mutableListOf("text" to text, "numResults" to "15", "language" to language)
        near?.let { q += "place" to LatLon.format(it.lat, 2) + "," + LatLon.format(it.lon, 2); q += "placeBias" to "5" }
        return json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(GeocodeMatch.serializer()), get("/api/v1/geocode", q, 3600))
    }

    /** Routes; `maxWalk` (s) to the first and from the last stop. `time` null = now. */
    suspend fun plan(from: LatLon, to: LatLon, time: java.time.Instant?, arriveBy: Boolean, maxWalk: Int = 1800,
                     pageCursor: String? = null): PlanResponse {
        val q = mutableListOf(
            "fromPlace" to from.apiValue, "toPlace" to to.apiValue, "arriveBy" to arriveBy.toString(),
            "useRoutedTransfers" to "true", "maxPreTransitTime" to maxWalk.toString(),
            "maxPostTransitTime" to maxWalk.toString(), "maxDirectTime" to "3600", "directModes" to "WALK",
        )
        time?.let { q += "time" to it.toString() }
        pageCursor?.let { q += "pageCursor" to it }
        return json.decodeFromString(PlanResponse.serializer(), get("/api/v6/plan", q, if (time == null) 20 else 60))
    }

    /** Stops in a box, for the station map. */
    suspend fun mapStops(min: LatLon, max: LatLon, zoom: Int): List<MapStop> =
        json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MapStop.serializer()),
            get("/api/v6/map/stops", listOf("min" to min.apiValue, "max" to max.apiValue, "zoom" to zoom.toString()), 3600))

    /** One vehicle's whole trip, with live stop times. */
    suspend fun trip(id: String): Itinerary =
        json.decodeFromString(Itinerary.serializer(), get("/api/v6/trip", listOf("tripId" to id), 15))

    @Serializable
    private data class LastNearby(val lat: Double, val lon: Double, val modes: String? = null, val at: Long, val body: String)

    /** The last Nearby board (shown at once while GPS and network start). */
    suspend fun lastNearby(maxAgeSeconds: Long, modes: String?): Pair<StopTimesResponse, LatLon>? = withContext(Dispatchers.IO) {
        val f = cacheDir?.let { File(it, "last-nearby.json") }?.takeIf { it.exists() } ?: return@withContext null
        runCatching {
            val last = json.decodeFromString(LastNearby.serializer(), f.readText())
            if (last.modes != modes || System.currentTimeMillis() - last.at > maxAgeSeconds * 1000) return@runCatching null
            json.decodeFromString(StopTimesResponse.serializer(), last.body) to LatLon(last.lat, last.lon)
        }.getOrNull()
    }

    private fun saveLastNearby(body: String, center: LatLon, modes: String?) {
        val dir = cacheDir ?: return
        runCatching {
            val tmp = File(dir, "last-nearby.json.tmp")
            tmp.writeText(json.encodeToString(LastNearby.serializer(), LastNearby(center.lat, center.lon, modes, System.currentTimeMillis(), body)))
            tmp.renameTo(File(dir, "last-nearby.json"))
        }
    }

    private suspend fun get(path: String, query: List<Pair<String, String>>, maxAgeSeconds: Long): String = withContext(Dispatchers.IO) {
        val qs = query.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val url = "$base$path?$qs"
        memory[url]?.let { if (System.currentTimeMillis() - it.at < maxAgeSeconds * 1000) return@withContext it.body }
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
            setRequestProperty("Accept", "application/json")
        }
        try {
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                val message = runCatching { json.decodeFromString(ServerError.serializer(), body).error }.getOrNull()
                throw ApiException(message ?: L10n.f("Server error (HTTP %lld).", status))
            }
            if (memory.size > 40) memory.clear()
            memory[url] = Entry(url, body, System.currentTimeMillis())
            body
        } finally {
            conn.disconnect()
        }
    }

    @Serializable private data class ServerError(val error: String)
}
