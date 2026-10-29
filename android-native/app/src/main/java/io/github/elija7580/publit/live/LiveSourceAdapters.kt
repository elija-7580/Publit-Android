package io.github.elija7580.publit.live

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.Alert
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A HAFAS journey planner backend (the one the operator's own app uses), as HafasSource on iOS.
 * ÖBB: live times for trains and in many regions buses and trams, plus disruption notes. The
 * German ones fill in rides Transitous has no live time for. Not official open APIs: if one
 * changes, rides simply keep their timetable times.
 */
class HafasSource(
    override val name: String,
    override val horizon: Long,
    override val onlyWithoutLive: Boolean,
    val endpoint: String,
    val client: Map<String, String>,
    val ext: String?,
    val ver: String,
    val aid: String,
    val salt: String? = null,
    /** German servers: DHID prefixes they have live data for. null = Austria. */
    val regions: List<String>? = null,
    val feeds: List<String> = emptyList(),
    val tz: String = "Europe/Vienna",
) : LiveSource {
    companion object {
        val area = GeoBox(46.37, 49.02, 9.53, 17.17)
        private val stops = TTLCache<String>(400)
        private val boards = TTLCache<List<LiveDeparture>>(200)

        val oebb = HafasSource("ÖBB", 6 * 3600, false, "https://fahrplan.oebb.at/bin/mgate.exe",
            mapOf("id" to "OEBB", "type" to "IPH", "name" to "oebbPROD-ADHOC", "v" to "6030600"), "OEBB.1", "1.57", "OWDL4fE4ixNiPBBm")
        val vbb = HafasSource("VBB", 3 * 3600, true, "https://fahrinfo.vbb.de/bin/mgate.exe",
            mapOf("type" to "WEB", "id" to "VBB", "name" to "VBB WebApp", "l" to "vs_webapp_vbb"), "VBB.1", "1.33", "hafas-vbb-webapp",
            regions = listOf("de:11", "de:12"), tz = "Europe/Berlin")
        val rmv = HafasSource("RMV", 3 * 3600, true, "https://www.rmv.de/auskunft/bin/jp/mgate.exe",
            mapOf("type" to "WEB", "id" to "RMV", "name" to "webapp", "l" to "vs_webapp"), "RMV.1", "1.44", "x0k4ZR33ICN9CWmj",
            regions = listOf("de:06"), tz = "Europe/Berlin")
        val vbn = HafasSource("VBN", 3 * 3600, true, "https://fahrplaner.vbn.de/bin/mgate.exe",
            mapOf("type" to "IPH", "id" to "VBN", "v" to "6000000", "name" to "vbn"), null, "1.42", "kaoxIXLn03zCr2KR", salt = "SP31mBufSyCLmNxp",
            regions = listOf("de:03", "de:04"), feeds = listOf("de-VBN_"), tz = "Europe/Berlin")
        val nahsh = HafasSource("NAH.SH", 3 * 3600, true, "https://nahsh.hafas.cloud/gate",
            mapOf("type" to "IPH", "id" to "NAHSH", "v" to "3000700", "name" to "NAHSHPROD"), null, "1.30", "r0Ot9FLFNAFxijLW",
            regions = listOf("de:01"), tz = "Europe/Berlin")

        /** Austria's border, roughly: the rectangle alone takes in Munich, Passau, Bratislava and Bolzano. */
        val outline = GeoOutline(listOf(
            9.53 to 47.27, 9.60 to 47.53, 10.0 to 47.58, 10.45 to 47.55, 10.9 to 47.48, 11.4 to 47.58, 12.2 to 47.72,
            12.75 to 47.66, 13.0 to 47.74, 12.96 to 47.86, 12.85 to 48.1, 13.03 to 48.27, 13.43 to 48.46, 13.73 to 48.52,
            13.84 to 48.77, 14.7 to 48.6, 15.0 to 49.02, 15.9 to 48.84, 16.9 to 48.62, 17.07 to 48.12, 17.16 to 48.0,
            16.9 to 47.7, 16.65 to 47.45, 16.45 to 47.0, 16.1 to 46.85, 16.0 to 46.67, 15.5 to 46.63, 14.9 to 46.6,
            14.5 to 46.42, 13.7 to 46.52, 12.4 to 46.65, 12.15 to 47.0, 11.6 to 46.98, 11.1 to 46.78, 10.45 to 46.86,
            10.1 to 46.85, 9.85 to 47.0, 9.53 to 47.06))

        private fun md5(s: ByteArray) = MessageDigest.getInstance("MD5").digest(s).joinToString("") { "%02x".format(it) }

        /** "?mic=md5(body)&mac=md5(mic + salt)", hex. */
        fun checksum(body: String, salt: String): String { val mic = md5(body.toByteArray()); return "mic=$mic&mac=${md5((mic + salt).toByteArray())}" }

        fun parseNearest(res: JSONObject): String? = J.objs(res.optJSONArray("locL")).minByOrNull { J.int(it, "dist") ?: Int.MAX_VALUE }?.let { J.str(it, "lid") }

        /** HAFAS time "HHMMSS" or "DDHHMMSS" (days after `date`), local with `offset` minutes from UTC. */
        fun clockTime(date: String?, value: String?, offset: Int?, tz: String = "Europe/Vienna"): Instant? {
            if (date == null || date.length != 8 || value == null || (value.length != 6 && value.length != 8)) return null
            val d = runCatching { LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() ?: return null
            val digits = if (value.length == 8) value.takeLast(6) else value
            val days = if (value.length == 8) value.take(2).toIntOrNull() ?: 0 else 0
            val h = digits.take(2).toIntOrNull() ?: return null; val mi = digits.substring(2, 4).toIntOrNull() ?: return null
            val s = digits.takeLast(2).toIntOrNull() ?: return null
            val local = d.atStartOfDay().plusDays(days.toLong()).plusSeconds(h * 3600L + mi * 60 + s)
            if (offset != null) return local.toInstant(java.time.ZoneOffset.ofTotalSeconds(offset * 60))
            return local.atZone(ZoneId.of(tz)).toInstant()
        }

        fun alert(him: JSONObject, tz: String): Alert? {
            val head = J.plain(J.str(him, "head") ?: ""); val text = J.plain(J.str(him, "text") ?: "")
            if (head.isEmpty() && text.isEmpty()) return null
            val prio = J.int(him, "prio") ?: 100
            return alertOf(head, text, if (prio <= 25) "SEVERE" else "WARNING",
                clockTime(J.str(him, "sDate"), J.str(him, "sTime"), null, tz), clockTime(J.str(him, "eDate"), J.str(him, "eTime"), null, tz))
        }

        fun parseBoard(res: JSONObject, tz: String = "Europe/Vienna"): List<LiveDeparture> {
            val common = res.optJSONObject("common")
            val prods = J.objs(common?.optJSONArray("prodL")); val hims = J.objs(common?.optJSONArray("himL"))
            return J.objs(res.optJSONArray("jnyL")).mapNotNull { j ->
                val s = j.optJSONObject("stbStop") ?: return@mapNotNull null
                val date = J.str(j, "date"); val offset = J.int(s, "dTZOffset")
                val scheduled = clockTime(date, J.str(s, "dTimeS"), offset, tz) ?: return@mapNotNull null
                val pi = J.int(j, "prodX") ?: J.int(s, "dProdX") ?: -1
                val prod = prods.getOrNull(pi)
                val ctx = prod?.optJSONObject("prodCtx")
                val lines = listOf(J.str(prod, "name"), J.str(prod, "number"), J.str(ctx, "line"), J.str(ctx, "num")).mapNotNull { it?.trim() }.filter { it.isNotEmpty() }
                val cls = J.int(prod, "cls")
                val alerts = J.objs(j.optJSONArray("msgL")).mapNotNull { m ->
                    if (J.str(m, "type") != "HIM") return@mapNotNull null
                    val him = J.int(m, "himX")?.let { hims.getOrNull(it) } ?: return@mapNotNull null
                    if (J.strs(m.optJSONArray("tagL")).any { "GLB" in it }) return@mapNotNull null
                    val mask = J.int(him, "prod")
                    if (mask != null && cls != null && mask and cls == 0) return@mapNotNull null
                    alert(him, tz)
                }
                val track = J.str(s.optJSONObject("dPltfR"), "txt") ?: J.str(s, "dPlatfR") ?: J.str(s.optJSONObject("dPltfS"), "txt") ?: J.str(s, "dPlatfS")
                LiveDeparture(lines, J.str(j, "dirTxt"), scheduled, clockTime(date, J.str(s, "dTimeR"), offset, tz),
                    s.optBoolean("dCncl") || j.optBoolean("isCncl"), track, alerts)
            }
        }
    }

    override fun covers(ride: LiveRide): Boolean {
        if (ride.tz != null && ride.tz != tz) return false
        regions?.let { r ->
            val stop = ride.stopId
            if (stop != null && feeds.any { stop.startsWith(it) }) return true
            val id = stop?.let(::dhid) ?: return false
            return r.any { id.startsWith(it) }
        }
        return area.contains(ride.point) && outline.contains(ride.point)
    }

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val p = ride.point
        val lid = stops.value(name + "|" + LatLon.format(p.lat, 4) + "," + LatLon.format(p.lon, 4), 86_400) { nearestStop(p) }
        if (lid.isEmpty()) return null
        val start = Instant.ofEpochSecond(ride.scheduled.epochSecond / 1800 * 1800 - 120)
        val deps = boards.value("$name|$lid|${start.epochSecond}", 45) { board(lid, start) }
        return LiveMatch.best(ride, deps)?.let { LiveMatch.fix(it, name) }
    }

    private fun body(method: String, req: JSONObject): String {
        val b = JSONObject().put("lang", "de").put("svcReqL", JSONArray().put(JSONObject().put("meth", method).put("req", req)))
            .put("client", JSONObject(client)).put("ver", ver).put("auth", JSONObject().put("type", "AID").put("aid", aid))
        ext?.let { b.put("ext", it) }
        return b.toString()
    }

    private suspend fun call(body: String): JSONObject {
        val url = salt?.let { endpoint + "?" + checksum(body, it) } ?: endpoint
        val root = JSONObject(LiveHTTP.text(url, body))
        val first = J.objs(root.optJSONArray("svcResL")).firstOrNull() ?: throw java.io.IOException("no result")
        if (J.str(first, "err") != "OK") throw java.io.IOException("HAFAS ${J.str(first, "err")}")
        return first.optJSONObject("res") ?: JSONObject()
    }

    /** "" when there is no stop within 300 m (cached like a hit: no point asking again). */
    private suspend fun nearestStop(p: LatLon): String = parseNearest(call(body("LocGeoPos", JSONObject()
        .put("ring", JSONObject().put("cCrd", JSONObject().put("x", (p.lon * 1e6).toInt()).put("y", (p.lat * 1e6).toInt())).put("maxDist", 300))
        .put("getPOIs", false).put("getStops", true).put("maxLoc", 3)))) ?: ""

    private suspend fun board(lid: String, start: Instant): List<LiveDeparture> {
        val local = start.atZone(ZoneId.of(tz))
        return parseBoard(call(body("StationBoard", JSONObject().put("type", "DEP")
            .put("date", local.format(DateTimeFormatter.ofPattern("yyyyMMdd"))).put("time", local.format(DateTimeFormatter.ofPattern("HHmmss")))
            .put("stbLoc", JSONObject().put("lid", lid)).put("dur", 60).put("maxJny", 120))), tz)
    }
}

/** Wiener Linien open data (CC BY 4.0, Stadt Wien – data.wien.gv.at): live countdowns and disruptions. */
object WienerLinienSource : LiveSource {
    override val name = "Wiener Linien"
    override val horizon = 70 * 60L
    private val area = GeoBox(48.11, 48.33, 16.18, 16.58)
    private val boards = TTLCache<List<LiveDeparture>>(80)

    override fun covers(ride: LiveRide) = area.contains(ride.point)

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val diva = ride.stopId?.let(::diva) ?: return null
        val deps = boards.value(diva, 30) { monitor(diva) }
        return LiveMatch.best(ride, deps)?.let { LiveMatch.fix(it, name) }
    }

    /** Transitous' Vienna stop IDs end in the operator's stop number: "…Pat:49:1198" is DIVA 60201198. */
    fun diva(stopId: String): String? {
        val i = stopId.lastIndexOf(":49:"); if (i < 0) return null
        val n = stopId.substring(i + 4).takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return if (n in 1 until 100_000) (60_200_000 + n).toString() else null
    }

    private fun stamp(s: String?): Instant? = s?.let {
        runCatching { OffsetDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")).toInstant() }.getOrNull()
    }

    private suspend fun monitor(diva: String): List<LiveDeparture> {
        val url = "https://www.wienerlinien.at/ogd_realtime/monitor?diva=" + URLEncoder.encode(diva, "UTF-8") +
            "&activateTrafficInfo=stoerungkurz&activateTrafficInfo=stoerunglang"
        return parseMonitor(JSONObject(LiveHTTP.text(url)))
    }

    fun parseMonitor(root: JSONObject): List<LiveDeparture> {
        val data = root.optJSONObject("data") ?: return emptyList()
        val infos = J.objs(data.optJSONArray("trafficInfos")).mapNotNull { info ->
            if (J.int(info, "refTrafficInfoCategoryId") == 1) return@mapNotNull null // lifts: not about rides
            val attrs = info.optJSONObject("attributes")
            val lines = J.strs(info.optJSONArray("relatedLines")) + J.strs(attrs?.optJSONArray("relatedLines"))
            val title = J.str(info, "title") ?: ""
            val text = listOfNotNull(J.str(info, "description"), J.str(attrs, "reason")).filter { it.isNotEmpty() }.joinToString("\n")
            if (title.isEmpty() && text.isEmpty()) return@mapNotNull null
            val t = info.optJSONObject("time")
            lines to alertOf(title, text, "WARNING", stamp(J.str(t, "start")), stamp(J.str(t, "end")))
        }
        val out = ArrayList<LiveDeparture>()
        for (monitor in J.objs(data.optJSONArray("monitors"))) for (line in J.objs(monitor.optJSONArray("lines"))) {
            val name = J.str(line, "name") ?: continue
            val alerts = infos.filter { name in it.first }.map { it.second }
            for (dep in J.objs(line.optJSONObject("departures")?.optJSONArray("departure"))) {
                val t = dep.optJSONObject("departureTime")
                val planned = stamp(J.str(t, "timePlanned")) ?: continue
                val v = dep.optJSONObject("vehicle")
                out += LiveDeparture(listOf(J.str(v, "name") ?: name), J.str(v, "towards") ?: J.str(line, "towards"), planned,
                    stamp(J.str(t, "timeReal")), alerts = alerts)
            }
        }
        return out
    }
}
