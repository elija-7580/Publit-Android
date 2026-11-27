package io.github.elija7580.publit.data

import android.content.Context
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/**
 * Measured delays for Smart Routing's change chances (port of iOS `DelayHistory.swift`): a table per
 * country from publit.eu (`services/delay-history`). Only public timetable data; the app downloads the
 * tables of the countries it plans in, like the coverage atlas, and sends nothing about the user.
 */
object DelayHistory {
    val KNOTS = doubleArrayOf(0.05, 0.20, 0.50, 0.80, 0.95)
    const val SAMPLES = 20

    /** A delay distribution in seconds, rebuilt from its 5/20/50/80/95 % quantiles. */
    data class Dist(val q: List<Double>) {
        init { require(q.size == 5) }

        /** Linear between the knots, linear outside them; the late tail twice as steep. */
        fun quantile(p: Double): Double {
            val k = KNOTS
            if (p <= k[0]) return q[0] - (k[0] - p) / (k[1] - k[0]) * (q[1] - q[0])
            if (p >= k[4]) return q[4] + 2 * (p - k[4]) / (k[4] - k[3]) * (q[4] - q[3])
            for (i in 0 until 4) if (p <= k[i + 1]) return q[i] + (p - k[i]) / (k[i + 1] - k[i]) * (q[i + 1] - q[i])
            return q[4]
        }

        fun samples(n: Int = SAMPLES) = DoubleArray(n) { quantile((it + 0.5) / n) }
        fun shifted(s: Double) = Dist(q.map { it + s })

        companion object {
            val PUNCTUAL = Dist(listOf(0.0, 0.0, 0.0, 0.0, 0.0))
            fun of(s: String): Dist? = s.split(',').mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 5 }?.let(::Dist)
        }
    }

    data class Row(val arrival: Dist?, val departure: Dist?, val cancelled: Double, val observations: Int)

    /** p weekday peak, w weekday other, e weekend, n night – as on the server. */
    fun window(t: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val local = t.atZone(zone)
        val h = local.hour
        if (h >= 22 || h < 5) return "n"
        if (local.dayOfWeek == DayOfWeek.SATURDAY || local.dayOfWeek == DayOfWeek.SUNDAY) return "e"
        if (h in 6 until 9 || h in 16 until 19) return "p"
        return "w"
    }

    /** "de" from "de-DELFI_de:09162:…". */
    fun country(id: String?): String? {
        val tag = id?.substringBefore('_')?.takeIf { it.isNotEmpty() } ?: return null
        val c = tag.substringBefore('-')
        return if (c.length in 2..6) c.lowercase() else null
    }

    data class Horizon(val minutes: Double, val error: Dist, val n: Int)

    class Table(text: String = "") {
        var date = ""; private set
        var days = 0; private set
        private val stops = HashMap<String, Row>()
        private val routes = HashMap<String, Row>()
        private val operators = HashMap<String, Row>()
        private val modes = HashMap<String, Row>()
        private val horizons = HashMap<String, MutableList<Horizon>>()

        val isEmpty get() = routes.isEmpty() && modes.isEmpty() && stops.isEmpty()

        init {
            fun row(f: List<String>, i: Int): Row? {
                if (f.size < i + 4) return null
                val c = f[i + 2].toDoubleOrNull() ?: return null
                val n = f[i + 3].toIntOrNull() ?: return null
                return Row(Dist.of(f[i]), Dist.of(f[i + 1]), c / 1000, n)
            }
            for (line in text.lineSequence()) {
                val f = line.split('|')
                when (f.firstOrNull()) {
                    "D" -> if (f.size >= 3) { date = f[1]; days = f[2].toIntOrNull() ?: 0 }
                    "S" -> if (f.size >= 8) row(f, 4)?.let { stops["${f[1]}|${f[2]}|${f[3]}"] = it }
                    "R" -> if (f.size >= 7) row(f, 3)?.let { routes["${f[1]}|${f[2]}"] = it }
                    "O" -> if (f.size >= 8) row(f, 4)?.let { operators["${f[1]}|${f[2]}|${f[3]}"] = it }
                    "M" -> if (f.size >= 7) row(f, 3)?.let { modes["${f[1]}|${f[2]}"] = it }
                    "H" -> if (f.size >= 5) {
                        val m = f[2].toDoubleOrNull(); val e = Dist.of(f[3]); val n = f[4].toIntOrNull()
                        if (m != null && e != null && n != null) horizons.getOrPut(f[1]) { ArrayList() }.apply { add(Horizon(m, e, n)); sortBy { it.minutes } }
                    }
                }
            }
        }

        /** Most specific first: route at this stop, route, operator, mode in the region. */
        fun row(routeId: String?, stopId: String?, agency: String?, mode: String, window: String): Row? {
            if (routeId != null) {
                if (stopId != null) stops["$routeId|$stopId|$window"]?.let { return it }
                routes["$routeId|$window"]?.let { return it }
                if (agency != null) operators["${routeId.substringBefore('_')}|$agency|$window"]?.let { return it }
            }
            return modes["$mode|$window"]
        }

        /** How far a live forecast this many minutes ahead is off (interpolated; shrinks to nothing at 0 min). */
        fun forecastError(mode: String, minutesAhead: Double): Pair<Dist, Int>? {
            val hs = horizons[mode] ?: return null
            val first = hs.firstOrNull() ?: return null
            val m = maxOf(0.0, minutesAhead)
            // A first horizon at 0 min would divide by zero (NaN, which later fails when rounded to a percent).
            if (m <= first.minutes) { val f = if (first.minutes > 0) m / first.minutes else 1.0; return Dist(first.error.q.map { it * f }) to first.n }
            for (i in 0 until hs.size - 1) {
                val a = hs[i]; val b = hs[i + 1]
                if (m <= b.minutes) {
                    val t = if (b.minutes > a.minutes) (m - a.minutes) / (b.minutes - a.minutes) else 1.0
                    return Dist(a.error.q.zip(b.error.q) { x, y -> x + t * (y - x) }) to minOf(a.n, b.n)
                }
            }
            return hs.last().error to hs.last().n
        }
    }

    fun gunzip(bytes: ByteArray): ByteArray? = runCatching { GZIPInputStream(bytes.inputStream()).use { it.readBytes() } }.getOrNull()
}

/** The delay tables of the countries the user plans in; each at most once a day, old file kept on failure. */
object DelayHistoryStore {
    private const val REMOTE = "https://publit.eu/verspaetung/data/%s.txt.gz"
    private val tables = HashMap<String, DelayHistory.Table>()
    private val checked = HashMap<String, Long>()
    @Volatile var dir: File? = null

    fun init(ctx: Context) { dir = File(ctx.filesDir, "delay-history").apply { mkdirs() } }

    @Synchronized fun table(country: String?): DelayHistory.Table? {
        country ?: return null
        tables[country]?.let { return it }
        val text = runCatching { File(dir ?: return null, "$country.txt").readText() }.getOrNull() ?: return null
        return DelayHistory.Table(text).takeIf { !it.isEmpty }?.also { tables[country] = it }
    }

    /** For tests. */
    @Synchronized fun install(table: DelayHistory.Table, country: String) { tables[country] = table }

    /** Accepts a downloaded table only if it parses and is not older than the one kept. */
    @Synchronized fun install(text: String, country: String): Boolean {
        val new = DelayHistory.Table(text)
        if (new.date.isEmpty() || new.isEmpty) return false
        table(country)?.let { if (it.date > new.date) return false }
        tables[country] = new
        dir?.let { d -> runCatching { File(d, "$country.txt").writeText(text) } }
        return true
    }

    /** Loads the tables for the countries of these routes (call off the main thread). */
    fun refresh(list: List<Itinerary>, nowMs: Long = System.currentTimeMillis()) {
        val countries = list.flatMap { it.legs }.mapNotNull { DelayHistory.country(it.routeId) }.toSet()
        for (c in countries) {
            val due = synchronized(this) {
                (checked[c]?.let { nowMs - it > 20 * 3_600_000L } ?: true).also { if (it) checked[c] = nowMs }
            }
            if (!due) continue
            runCatching {
                val conn = java.net.URL(REMOTE.format(c)).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 30_000
                try {
                    if (conn.responseCode == 200) {
                        val raw = conn.inputStream.readBytes()
                        val text = (DelayHistory.gunzip(raw) ?: raw).toString(Charsets.UTF_8)
                        if (text.startsWith("D|")) install(text, c)
                    }
                } finally { conn.disconnect() }
            }
        }
    }
}
