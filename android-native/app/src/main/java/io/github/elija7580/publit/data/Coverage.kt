package io.github.elija7580.publit.data

import android.content.Context
import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlin.math.cos

/** How badly the open timetable data falls short around a place. Ordered by severity. */
enum class CoverageLevel { PARTIAL, SPARSE, NONE }

/** A kind of service the open timetables do not contain in an area. */
enum class CoverageGap(val code: Char, private val key: String) {
    LOCAL_BUSES('L', "Local and regional buses"), CITY_BUSES('C', "City buses"), METRO('M', "Metro"),
    TRAMS('T', "Trams"), FERRIES('F', "Ferries"), SUBURBAN('S', "Suburban trains");
    val title: String get() = L10n.t(key)
    companion object {
        fun list(gaps: List<CoverageGap>): String {
            val de = L10n.locale.language == "de"
            val names = gaps.map { if (de) it.title else it.title.replaceFirstChar { c -> c.lowercase() } }
            return when (names.size) { 0 -> ""; 1 -> names[0]; else -> names.dropLast(1).joinToString(", ") + " " + L10n.t("and") + " " + names.last() }
        }
    }
}

/** A place or region where data is known to be missing (CoverageArea on iOS). */
class CoverageArea(val id: String, val level: CoverageLevel, val gaps: List<CoverageGap>, val nameEN: String, val nameDE: String,
                   val link: String, val circle: Pair<LatLon, Double>?, val rings: List<List<LatLon>>) {
    val minLat: Double; val maxLat: Double; val minLon: Double; val maxLon: Double
    init {
        if (circle != null) {
            val (c, r) = circle
            val dLat = r / 111_200; val dLon = r / (111_200 * maxOf(0.05, cos(Math.toRadians(c.lat))))
            minLat = c.lat - dLat; maxLat = c.lat + dLat; minLon = c.lon - dLon; maxLon = c.lon + dLon
        } else {
            val pts = rings.flatten()
            minLat = pts.minOf { it.lat }; maxLat = pts.maxOf { it.lat }; minLon = pts.minOf { it.lon }; maxLon = pts.maxOf { it.lon }
        }
    }
    val name: String get() = if (L10n.locale.language == "de") nameDE else nameEN
    val mapUrl: String get() = "https://publit.eu/abdeckung/?lang=" + (if (L10n.locale.language == "de") "de" else "en") + (if (link.isNotEmpty()) "#$link" else "")

    fun intersects(a: Double, b: Double, c: Double, d: Double) = !(maxLat < a || minLat > b || maxLon < c || minLon > d)

    fun contains(p: LatLon): Boolean {
        if (p.lat !in minLat..maxLat || p.lon !in minLon..maxLon) return false
        circle?.let { (c, r) ->
            val dy = (p.lat - c.lat) * 111_200; val dx = (p.lon - c.lon) * 111_200 * cos(Math.toRadians(c.lat))
            return dx * dx + dy * dy <= r * r
        }
        return rings.count { ring(it, p) } % 2 == 1
    }

    /** Outline rings for drawing (a circle as a polygon); holes follow the even-odd rule. */
    val outline: List<List<LatLon>> by lazy {
        circle?.let { (c, r) ->
            listOf((0..48).map { i -> val a = 2 * Math.PI * i / 48
                LatLon(c.lat + r / 111_200 * Math.sin(a), c.lon + r / (111_200 * cos(Math.toRadians(c.lat))) * Math.cos(a)) })
        } ?: rings
    }

    /** "Missing: metro, trams and ferries" / "No timetable data" / "Very little timetable data". */
    val summary: String get() = when (level) {
        CoverageLevel.NONE -> L10n.t("No timetable data")
        CoverageLevel.SPARSE -> L10n.t("Very little timetable data")
        CoverageLevel.PARTIAL -> if (gaps.isEmpty()) L10n.t("Very little timetable data") else L10n.f("Missing: %@", CoverageGap.list(gaps))
    }

    companion object {
        fun ring(r: List<LatLon>, p: LatLon): Boolean {
            if (r.size < 3) return false
            var inside = false; var j = r.size - 1
            for (i in r.indices) {
                val a = r[i]; val b = r[j]
                if ((a.lat > p.lat) != (b.lat > p.lat) && p.lon < (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon) inside = !inside
                j = i
            }
            return inside
        }
    }
}

/** All known gaps, bundled in the app (assets/coverage.txt, generated with the iOS tools): offline, nothing leaves the phone. */
object CoverageAtlas {
    @Volatile var areas: List<CoverageArea> = emptyList(); private set
    var date = ""; private set

    fun load(ctx: Context) {
        val lines = runCatching { ctx.assets.open("coverage.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
        date = lines.firstOrNull().orEmpty()
        areas = lines.drop(1).mapNotNull(::parse)
    }

    /** One area per line: A|id|level|gaps|name en|name de|link|shape. */
    fun parse(line: String): CoverageArea? {
        val f = line.split("|", limit = 8)
        if (f.size != 8 || f[0] != "A") return null
        val level = when (f[2]) { "n" -> CoverageLevel.NONE; "s" -> CoverageLevel.SPARSE; "p" -> CoverageLevel.PARTIAL; else -> return null }
        val gaps = f[3].mapNotNull { c -> CoverageGap.entries.firstOrNull { it.code == c } }
        val shape = f[7]
        return when (shape.firstOrNull()) {
            'c' -> shape.drop(1).split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 3 }?.let { v ->
                CoverageArea(f[1], level, gaps, f[4], f[5], f[6], LatLon(v[0] / 1000, v[1] / 1000) to v[2], emptyList()) }
            'p' -> shape.drop(1).split("/").map(::ring).filter { it.size > 2 }.takeIf { it.isNotEmpty() }?.let { CoverageArea(f[1], level, gaps, f[4], f[5], f[6], null, it) }
            else -> null
        }
    }

    private const val DIGITS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Zigzag varints, 5 bits per character (bit 5 = more follows); latitude and longitude deltas alternate. */
    private fun ring(s: String): List<LatLon> {
        val values = ArrayList<Int>(); var acc = 0; var shift = 0
        for (ch in s) {
            val v = DIGITS.indexOf(ch); if (v < 0) continue
            acc = acc or ((v and 31) shl shift)
            if (v >= 32) { shift += 5; continue }
            values += if (acc and 1 == 1) -((acc + 1) shr 1) else acc shr 1
            acc = 0; shift = 0
        }
        var lat = 0; var lon = 0; val out = ArrayList<LatLon>()
        var i = 0
        while (i + 1 < values.size) { lat += values[i]; lon += values[i + 1]; out += LatLon(lat / 1000.0, lon / 1000.0); i += 2 }
        return out
    }

    /** The most specific area first: hand-checked areas before towns before regions. */
    fun area(p: LatLon?): CoverageArea? = p?.let { q -> areas.firstOrNull { it.contains(q) } }
    fun areas(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) = areas.filter { it.intersects(minLat, maxLat, minLon, maxLon) }
}

/** A warning to show: one area, and which of the searched places lie in it. */
data class CoverageNotice(val area: CoverageArea, val roles: Set<Role>) {
    enum class Role { HERE, START, DESTINATION }
    val heading: String get() = when (roles) {
        setOf(Role.START) -> L10n.t("From") + " · " + area.name
        setOf(Role.DESTINATION) -> L10n.t("To") + " · " + area.name
        else -> area.name
    }
}

object CoverageGuide {
    /** Warnings for the places of a search, worst first, each area once. */
    fun notices(places: List<Pair<LatLon?, CoverageNotice.Role>>): List<CoverageNotice> {
        val by = LinkedHashMap<String, Pair<CoverageArea, MutableSet<CoverageNotice.Role>>>()
        for ((p, role) in places) { val a = CoverageAtlas.area(p) ?: continue; by.getOrPut(a.id) { a to mutableSetOf() }.second += role }
        return by.values.sortedByDescending { it.first.level }.map { CoverageNotice(it.first, it.second) }
    }
    fun notices(start: LatLon?, destination: LatLon?) = notices(listOf(start to CoverageNotice.Role.START, destination to CoverageNotice.Role.DESTINATION))
    fun notice(at: LatLon?) = notices(listOf(at to CoverageNotice.Role.HERE)).firstOrNull()

    /** A whole trip: start, destination and the stops where it changes. On foot, bike or car it does not depend on timetables. */
    fun notices(it: Itinerary): List<CoverageNotice> {
        if (it.legs.none { l -> l.isTransit }) return emptyList()
        val first = it.legs.first(); val last = it.legs.last()
        return notices(listOf<Pair<LatLon?, CoverageNotice.Role>>(first.from.coordinate to CoverageNotice.Role.START) +
            it.legs.drop(1).filter { l -> l.isTransit }.map { l -> l.from.coordinate to CoverageNotice.Role.HERE } + (last.to.coordinate to CoverageNotice.Role.DESTINATION))
    }
}
