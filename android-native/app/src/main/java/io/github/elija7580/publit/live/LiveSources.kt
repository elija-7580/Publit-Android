package io.github.elija7580.publit.live

import io.github.elija7580.publit.BuildConfigInfo
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Alert
import io.github.elija7580.publit.data.AlertPeriod
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.StopTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.awaitAll
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

// Extra real-time sources for regions where Transitous has timetables but (almost) no live data,
// as LiveSources.swift. Transitous stays the router; these only correct times, cancellations,
// platforms and add alerts to rides it already found. A live time from Transitous always wins.

/** One ride to look up: a vehicle leaving a stop at a timetable time. */
data class LiveRide(
    val point: LatLon, val stopId: String?, val stopName: String, val lines: List<String>, val headsign: String?,
    val scheduled: Instant, val mode: String, val tz: String? = null, val live: Boolean = false,
)

/** A departure as an extra source reports it. */
data class LiveDeparture(
    val lines: List<String>, val destination: String? = null, val scheduled: Instant? = null, val expected: Instant? = null,
    val cancelled: Boolean = false, val track: String? = null, val alerts: List<Alert> = emptyList(),
)

/** What a source knows about one ride. */
data class LiveFix(
    val departure: Instant? = null, val arrival: Instant? = null, val cancelled: Boolean = false,
    val track: String? = null, val alerts: List<Alert> = emptyList(), val source: String,
) { val hasTimes get() = departure != null || cancelled }

interface LiveSource {
    val name: String
    /** How far ahead the source knows anything useful (s). */
    val horizon: Long
    /** Only asked for rides without a live time from Transitous. */
    val onlyWithoutLive: Boolean get() = false
    fun covers(ride: LiveRide): Boolean
    suspend fun fix(ride: LiveRide, now: Instant): LiveFix?
    fun asks(ride: LiveRide, now: Instant) = !(onlyWithoutLive && ride.live) && ride.scheduled < now.plusSeconds(horizon) && covers(ride)
}

class GeoBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun contains(p: LatLon) = p.lat in minLat..maxLat && p.lon in minLon..maxLon
}

/** A rough outline (lon, lat), for areas a rectangle would overshoot. */
class GeoOutline(val points: List<Pair<Double, Double>>) {
    fun contains(p: LatLon): Boolean {
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val (alon, alat) = points[i]; val (blon, blat) = points[j]
            if ((alat > p.lat) != (blat > p.lat) && p.lon < (blon - alon) * (p.lat - alat) / (blat - alat) + alon) inside = !inside
            j = i
        }
        return inside
    }
}

object LiveMatch {
    private val prefixes = listOf("bus ", "tram ", "str ", "strassenbahn ", "linie ", "line ", "zug-nr. ", "u-bahn ", "night bus ")

    /** "Bus 404" → "404", "S 4 (Zug-Nr. 5197)" → "s4", "13_UN" → "13", "Elizabeth line" → "elizabeth". */
    fun key(name: String): String {
        var t = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        t.indexOf('(').takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        t.indexOf('_').takeIf { it >= 0 }?.let { t = t.substring(0, it) }
        t = t.trim()
        prefixes.firstOrNull { t.startsWith(it) }?.let { t = t.removePrefix(it) }
        t = t.filter { it.isLetterOrDigit() }
        if (t.length > 4 && t.endsWith("line")) t = t.dropLast(4)
        return t
    }

    /** Runs of 3+ digits: train numbers ("REX 7 2345" → 2345). */
    fun numbers(name: String): Set<String> = Regex("[0-9]{3,}").findAll(name).map { it.value }.toSet()

    fun sameLine(a: List<String>, b: List<String>): Boolean {
        val ka = a.map(::key).filter { it.isNotEmpty() }.toSet(); val kb = b.map(::key).filter { it.isNotEmpty() }.toSet()
        if (ka.any { it in kb }) return true
        val na = a.flatMap(::numbers).toSet(); val nb = b.flatMap(::numbers).toSet()
        return na.isNotEmpty() && na.any { it in nb }
    }

    fun best(ride: LiveRide, list: List<LiveDeparture>, tolerance: Long = 90): LiveDeparture? {
        val same = list.filter { sameLine(ride.lines, it.lines) }
        same.mapNotNull { d -> d.scheduled?.let { s -> abs(s.epochSecond - ride.scheduled.epochSecond).takeIf { it <= tolerance }?.let { d to it } } }
            .minByOrNull { it.second }?.let { return it.first }
        return same.mapNotNull { d ->
            if (d.scheduled != null) return@mapNotNull null
            val e = d.expected ?: return@mapNotNull null
            val late = e.epochSecond - ride.scheduled.epochSecond
            if (late >= -tolerance && late <= 20 * 60) d to abs(late) else null
        }.minByOrNull { it.second }?.first
    }

    fun fix(d: LiveDeparture, source: String) = LiveFix(departure = d.expected, cancelled = d.cancelled, track = d.track, alerts = d.alerts, source = source)
}

object LiveMerge {
    fun adding(new: List<Alert>, old: List<Alert>?): List<Alert>? {
        if (new.isEmpty()) return old
        val list = old.orEmpty().toMutableList()
        fun k(a: Alert) = LiveMatch.key(a.headerText) + "|" + LiveMatch.key(a.descriptionText.take(80))
        val seen = list.map(::k).toMutableSet()
        for (a in new) if (seen.add(k(a))) list += a
        return list
    }

    fun merge(leg: Leg, fix: LiveFix): Leg {
        var l = leg
        if (!leg.realTime) {
            fix.departure?.let { dep ->
                val sStart = leg.scheduledStartTime ?: leg.startTime
                val delay = dep.epochSecond - sStart.epochSecond
                val arrival = fix.arrival ?: leg.scheduledEnd.plusSeconds(maxOf(0, delay))
                val end = maxOf(arrival, dep)
                val shift = maxOf(0L, delay)
                l = l.copy(startTime = dep, endTime = end, duration = (end.epochSecond - dep.epochSecond).toInt(),
                    from = l.from.copy(departure = dep), to = l.to.copy(arrival = end),
                    intermediateStops = leg.intermediateStops?.map { s ->
                        s.copy(arrival = (s.scheduledArrival ?: s.arrival)?.plusSeconds(shift), departure = (s.scheduledDeparture ?: s.departure)?.plusSeconds(shift))
                    }, realTime = true, liveSource = fix.source)
            }
            if (fix.cancelled) l = l.copy(cancelled = true, liveSource = fix.source)
            fix.track?.takeIf { it.isNotEmpty() }?.let { l = l.copy(from = l.from.copy(track = it)) }
        }
        return l.copy(alerts = adding(fix.alerts, leg.alerts))
    }

    private fun shifted(leg: Leg, delta: Long): Leg = if (delta == 0L) leg else leg.copy(
        startTime = leg.startTime.plusSeconds(delta), endTime = leg.endTime.plusSeconds(delta),
        from = leg.from.copy(departure = leg.from.departure?.plusSeconds(delta)), to = leg.to.copy(arrival = leg.to.arrival?.plusSeconds(delta)))

    /** Walks after a corrected ride move with its arrival; the walk to the first ride with its departure. */
    fun merge(it: Itinerary, fixes: Map<Int, LiveFix>): Itinerary {
        if (fixes.isEmpty()) return it
        val out = it.legs.toMutableList()
        val firstTransit = it.legs.indexOfFirst { l -> l.isTransit }
        var carry = 0L
        for (i in it.legs.indices) {
            val old = it.legs[i]
            val fix = fixes[i]
            if (fix != null && old.isTransit) {
                val new = merge(old, fix)
                out[i] = new
                if (i == firstTransit) {
                    val lead = new.startTime.epochSecond - old.startTime.epochSecond
                    for (j in 0 until i) if (!it.legs[j].isTransit) out[j] = shifted(it.legs[j], lead)
                }
                carry = new.endTime.epochSecond - old.endTime.epochSecond
            } else if (old.isTransit) carry = 0
            else if (carry != 0L && firstTransit in 0 until i) out[i] = shifted(old, carry)
        }
        val first = out.first(); val last = out.last()
        return it.copy(legs = out, startTime = first.startTime, endTime = last.endTime, duration = (last.endTime.epochSecond - first.startTime.epochSecond).toInt())
    }

    fun merge(st: StopTime, fix: LiveFix): StopTime {
        var s = st
        if (!st.realTime) {
            fix.departure?.let { dep ->
                val a = st.place.scheduledArrival ?: st.place.arrival
                val sd = st.scheduledDepartureTime
                val arr = if (a != null && sd != null) a.plusSeconds(maxOf(0, dep.epochSecond - sd.epochSecond)) else st.place.arrival
                s = s.copy(place = s.place.copy(departure = dep, arrival = arr), realTime = true, liveSource = fix.source)
            }
            if (fix.cancelled) s = s.copy(cancelled = true, liveSource = fix.source)
            fix.track?.takeIf { it.isNotEmpty() }?.let { s = s.copy(place = s.place.copy(track = it)) }
        }
        return s.copy(place = s.place.copy(alerts = adding(fix.alerts, st.place.alerts)))
    }
}

object LiveOverlay {
    @Volatile var enabled = true

    val sources: List<LiveSource> = listOf(WienerLinienSource, HafasSource.oebb, HafasSource.vbb, HafasSource.rmv, HafasSource.vbn, HafasSource.nahsh)
    val sourceNames get() = sources.map { it.name }

    fun ride(leg: Leg) = LiveRide(leg.from.coordinate, leg.from.stopId, leg.from.name, listOfNotNull(leg.routeShortName, leg.displayName).filter { it.isNotEmpty() },
        leg.headsign, leg.scheduledStartTime ?: leg.startTime, leg.mode, leg.from.tz, leg.realTime)

    fun ride(st: StopTime) = LiveRide(st.place.coordinate, st.place.stopId, st.place.name, listOfNotNull(st.routeShortName, st.displayName).filter { it.isNotEmpty() },
        st.headsign, st.scheduledDepartureTime ?: st.departureTime, st.mode, st.place.tz, st.realTime)

    fun wanted(r: LiveRide, now: Instant) = r.scheduled > now.minusSeconds(30 * 60) && sources.any { it.asks(r, now) }

    /** Times from the first source that has them, alerts from all that cover the ride. */
    suspend fun fix(r: LiveRide, now: Instant): LiveFix? {
        var result: LiveFix? = null
        val alerts = ArrayList<Alert>()
        for (src in sources) {
            if (!src.asks(r, now)) continue
            val f = runCatching { src.fix(r, now) }.getOrNull() ?: continue
            alerts += f.alerts
            if (result == null && f.hasTimes) result = f
            if (result != null) break
        }
        if (result == null && alerts.isNotEmpty()) result = LiveFix(source = "")
        return result?.copy(alerts = LiveMerge.adding(alerts, null).orEmpty())
    }

    /** Rides side by side; what arrived within `budget` ms. Late answers still fill the caches. */
    @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
    suspend fun lookup(rides: List<LiveRide>, now: Instant, budget: Long): Map<LiveRide, LiveFix> {
        if (rides.isEmpty()) return emptyMap()
        val results = ConcurrentHashMap<LiveRide, LiveFix>()
        val jobs = rides.map { r -> GlobalScope.async(Dispatchers.IO) { fix(r, now)?.let { results[r] = it } } }
        withTimeoutOrNull(budget) { jobs.awaitAll() }
        return HashMap(results)
    }

    suspend fun apply(list: List<Itinerary>, now: Instant = Instant.now(), budget: Long = 4000, maxRides: Int = 24): List<Itinerary> {
        if (!enabled) return list
        val rides = LinkedHashSet<LiveRide>()
        for (it in list) for (leg in it.legs) if (leg.isTransit) { val r = ride(leg); if (rides.size < maxRides && wanted(r, now)) rides += r }
        if (rides.isEmpty()) return list
        val fixes = lookup(rides.toList(), now, budget)
        if (fixes.isEmpty()) return list
        return list.map { it ->
            val byLeg = HashMap<Int, LiveFix>()
            it.legs.forEachIndexed { i, leg -> if (leg.isTransit) fixes[ride(leg)]?.let { f -> byLeg[i] = f } }
            LiveMerge.merge(it, byLeg)
        }
    }

    suspend fun apply(it: Itinerary, budget: Long = 4000) = apply(listOf(it), budget = budget).first()

    suspend fun applyStopTimes(list: List<StopTime>, now: Instant = Instant.now(), budget: Long = 2000, maxRides: Int = 40): List<StopTime> {
        if (!enabled) return list
        val rides = LinkedHashSet<LiveRide>()
        for (st in list) { val r = ride(st); if (rides.size < maxRides && wanted(r, now)) rides += r }
        if (rides.isEmpty()) return list
        val fixes = lookup(rides.toList(), now, budget)
        if (fixes.isEmpty()) return list
        return list.map { st -> fixes[ride(st)]?.let { LiveMerge.merge(st, it) } ?: st }
    }

    /** Sources that corrected a route, for "Live times: ÖBB". */
    fun sourceNames(it: Itinerary) = it.legs.mapNotNull { l -> l.liveSource?.takeIf { s -> s.isNotEmpty() } }.distinct()
}

/** Answers kept a short time; one download for overlapping requests; a failure is not repeated for a minute. */
class TTLCache<V : Any>(private val limit: Int = 200) {
    private val store = ConcurrentHashMap<String, Pair<Long, V>>()
    private val failed = ConcurrentHashMap<String, Long>()
    private val flights = ConcurrentHashMap<String, Deferred<V>>()

    suspend fun value(key: String, ttlSeconds: Long, load: suspend () -> V): V {
        val now = System.currentTimeMillis()
        store[key]?.let { if (it.first > now) return it.second }
        failed[key]?.let { if (it > now) throw java.io.IOException("recently failed") }
        val mine = CompletableDeferred<V>()
        val existing = flights.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        return try {
            val v = load()
            if (store.size >= limit) { store.entries.removeIf { it.value.first <= now }; if (store.size >= limit) store.clear() }
            store[key] = (now + ttlSeconds * 1000) to v
            mine.complete(v); v
        } catch (e: Exception) {
            failed[key] = System.currentTimeMillis() + 60_000
            mine.completeExceptionally(e); throw e
        } finally { flights.remove(key) }
    }
}

object LiveHTTP {
    suspend fun text(url: String, post: String? = null): String = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 6000; useCaches = false
            setRequestProperty("User-Agent", BuildConfigInfo.userAgent); setRequestProperty("Accept", "application/json")
            if (post != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        try {
            post?.let { c.outputStream.use { o -> o.write(it.toByteArray()) } }
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}

/** Small helpers for loosely typed JSON. */
object J {
    fun objs(a: JSONArray?): List<JSONObject> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
    fun strs(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { a!!.opt(it)?.toString() }
    fun str(o: JSONObject?, k: String): String? = o?.opt(k)?.takeIf { it != JSONObject.NULL }?.toString()
    fun int(o: JSONObject?, k: String): Int? = o?.opt(k)?.let { (it as? Number)?.toInt() ?: it.toString().toIntOrNull() }

    /** Plain text from operator HTML ("<br>", "<b>", "&#60;"). */
    fun plain(html: String): String {
        var s = html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]*>"), "")
        for ((e, c) in listOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&#39;" to "'", "&#60;" to "<", "&#62;" to ">"))
            s = s.replace(e, c)
        return s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
    }
}

/** Stop IDs in the German DHID form ("de:05113:9289"), from Transitous' IDs. */
fun dhid(stopId: String): String? {
    val i = stopId.indexOf("de:"); if (i < 0) return null
    val parts = stopId.substring(i).split(':')
    if (parts.size < 3 || parts[1].length != 5 || !parts[1].all { it.isDigit() }) return null
    val stop = parts[2].takeWhile { it.isLetterOrDigit() }
    return if (stop.isEmpty()) null else "de:${parts[1]}:$stop"
}

fun alertOf(head: String, text: String, severity: String, start: Instant?, end: Instant?) = Alert(
    headerText = head.ifEmpty { text }, descriptionText = if (head.isEmpty()) "" else text, url = null, severityLevel = severity,
    impactPeriod = if (start == null && end == null) null else listOf(AlertPeriod(start, end)))
