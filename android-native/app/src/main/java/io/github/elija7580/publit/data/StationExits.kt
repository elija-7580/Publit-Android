package io.github.elija7580.publit.data

import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A numbered or lettered way out of a station ("A", "B2", "12") from OpenStreetMap (StationEntrance on iOS). */
data class StationEntrance(val id: Long, val ref: String, val coordinate: LatLon)

/**
 * The signed exit number for walks that come up from an underground station: "Take exit B2" (StationExits on iOS).
 * OpenStreetMap has it as `ref` on `railway=subway_entrance`; asked from Overpass once per station, kept for the session.
 */
object StationExits {
    const val RADIUS = 250.0
    const val NEAR_EXIT = 40.0
    const val NEAR_PATH = 20.0
    /** No door on the way: the signed one at most this much out of the way towards where the walk heads. */
    const val MAX_DETOUR = 150.0
    /** Where the walk heads, for that: this far along it (or its end). */
    const val HEADING_AHEAD = 300.0
    private val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter")

    private val areas = ConcurrentHashMap<String, List<StationEntrance>>()
    private val walks = ConcurrentHashMap<String, String>()
    private val failed = ConcurrentHashMap<String, Long>()

    /** The walk after a ride that ends underground, unless it only changes to another underground platform. */
    fun leavesUnderground(legs: List<Leg>, i: Int): Boolean {
        if (i <= 0 || i >= legs.size || legs[i].isTransit) return false
        val walk = legs[i]; val before = legs[i - 1]
        if (!before.isTransit) return false
        val levels = walk.steps.orEmpty().flatMap { listOf(it.fromLevel, it.toLevel) }.filterNotNull()
        val startsBelow = (levels.firstOrNull() ?: 0.0) < 0
        if (!startsBelow && before.mode != "SUBWAY") return false
        if (levels.isNotEmpty() && startsBelow) return levels.any { it >= 0 }
        val next = legs.getOrNull(i + 1)
        if (next != null && next.mode == "SUBWAY" && (walk.distance ?: Double.MAX_VALUE) < 250) return false
        return true
    }

    private fun key(p: LatLon) = String.format(Locale.ROOT, "%.5f,%.5f", p.lat, p.lon)
    fun walkKey(leg: Leg) = key(leg.from.coordinate) + ">" + key(leg.to.coordinate)
    fun areaKey(p: LatLon) = String.format(Locale.ROOT, "%.3f,%.3f", p.lat, p.lon)

    fun entrances(leg: Leg): List<StationEntrance>? = walks[walkKey(leg)]?.let { areas[it] }

    fun store(entrances: List<StationEntrance>, leg: Leg) {
        val area = areaKey(leg.from.coordinate)
        walks[walkKey(leg)] = area; areas[area] = entrances
    }

    /** Looks up the stations the itinerary leaves on foot; true when it has exit numbers. */
    suspend fun load(it: Itinerary): Boolean = withContext(Dispatchers.IO) {
        var found = false
        for (i in it.legs.indices) {
            if (!leavesUnderground(it.legs, i)) continue
            val leg = it.legs[i]
            val area = areaKey(leg.from.coordinate)
            walks[walkKey(leg)] = area
            if (areas.containsKey(area)) { found = found || areas[area].orEmpty().isNotEmpty(); continue }
            val f = failed[area]
            if (f != null && System.currentTimeMillis() - f < 300_000) continue
            val result = request(leg.from.coordinate)
            if (result == null) failed[area] = System.currentTimeMillis()
            else { areas[area] = result; found = found || result.isNotEmpty() }
        }
        found
    }

    fun query(p: LatLon): String {
        val at = String.format(Locale.ROOT, "%d,%.6f,%.6f", RADIUS.toInt(), p.lat, p.lon)
        return "[out:json][timeout:10];" +
            "(node(around:$at)[\"railway\"~\"^(subway_entrance|train_station_entrance)$\"][\"ref\"];" +
            "node(around:$at)[\"public_transport\"=\"entrance\"][\"ref\"];);out;"
    }

    private fun request(p: LatLon): List<StationEntrance>? {
        val body = "data=" + URLEncoder.encode(query(p), "UTF-8")
        for (e in endpoints) {
            val text = runCatching {
                val conn = (URL(e).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 15_000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
                }
                try { conn.outputStream.use { it.write(body.toByteArray()) }; if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() } }
                finally { conn.disconnect() }
            }.getOrNull() ?: continue
            return runCatching { parse(text) }.getOrNull() ?: continue
        }
        return null
    }

    fun parse(text: String): List<StationEntrance> {
        val els = JSONObject(text).getJSONArray("elements")
        return (0 until els.length()).mapNotNull { i ->
            val el = els.optJSONObject(i) ?: return@mapNotNull null
            if (!el.has("lat") || !el.has("lon")) return@mapNotNull null
            val tags = el.optJSONObject("tags")
            val ref = (tags?.optString("ref")?.let(::clean) ?: tags?.optString("local_ref")?.let(::clean)) ?: return@mapNotNull null
            StationEntrance(el.optLong("id"), ref, LatLon(el.getDouble("lat"), el.getDouble("lon")))
        }
    }

    /** Exit numbers are short ("A", "B2", "12"); longer refs are internal codes, not signs. */
    fun clean(ref: String): String? = ref.trim().takeIf { it.isNotEmpty() && it.length <= 6 && ';' !in it }

    /** The entrance the route passes through, else the nearest to where it reaches the street, else the first next to the path. */
    fun pick(entrances: List<StationEntrance>, path: List<LatLon>, exitPoint: LatLon?, exitAlong: Double?, nodes: Set<Long>): StationEntrance? {
        if (entrances.isEmpty()) return null
        fun along(e: StationEntrance) = PathGeometry.locate(e.coordinate, path)
        entrances.filter { it.id in nodes }.minByOrNull { along(it)?.along ?: 0.0 }?.let { return it }
        if (exitPoint != null) {
            val near = entrances.minByOrNull { it.coordinate.distanceTo(exitPoint) }
            if (near != null && near.coordinate.distanceTo(exitPoint) <= NEAR_EXIT) return near
        }
        val limit = exitAlong?.let { it + NEAR_EXIT } ?: Double.MAX_VALUE
        entrances.mapNotNull { e -> along(e)?.takeIf { it.offset <= NEAR_PATH && it.along <= limit }?.let { e to it.along } }
            .minByOrNull { it.second }?.first?.let { return it }
        // No door on the way (rough walk path): the door least out of the way towards where the walk heads.
        val start = path.firstOrNull() ?: return null
        val ahead = PathGeometry.point(minOf(PathGeometry.length(path), HEADING_AHEAD), path) ?: return null
        val straight = start.distanceTo(ahead)
        if (straight <= NEAR_EXIT) return null
        fun detour(e: StationEntrance) = start.distanceTo(e.coordinate) + e.coordinate.distanceTo(ahead) - straight
        return entrances.filter { detour(it) <= MAX_DETOUR }.minByOrNull { detour(it) }
    }
}
