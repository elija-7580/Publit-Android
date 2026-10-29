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

private fun lt(s: String?, pattern: String, tz: String): Instant? = s?.let {
    runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern(pattern)).atZone(ZoneId.of(tz)).toInstant() }.getOrNull()
}
private fun iso(s: String?): Instant? = s?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() ?: runCatching { Instant.parse(it) }.getOrNull() }
private fun JSONObject.dbl(k: String): Double? = opt(k)?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }
private fun alertWith(head: String, text: String, severity: String, url: String? = null, effect: String? = null,
                      periods: List<io.github.elija7580.publit.data.AlertPeriod>? = null) =
    Alert(headerText = head.ifEmpty { text }, descriptionText = if (head.isEmpty()) "" else text, url = url, effect = effect,
        severityLevel = severity, impactPeriod = periods?.takeIf { it.isNotEmpty() })

/** ACTV's live departure screens (Venice: vaporetti and buses), unofficial. */
object ACTVSource : LiveSource {
    override val name = "ACTV"
    override val horizon = 90 * 60L
    private val area = GeoBox(45.2, 45.7, 12.0, 12.7)
    private const val TZ = "Europe/Rome"
    private data class Stop(val id: String, val point: LatLon)
    private val stopLists = TTLCache<List<Stop>>(4)
    private val boards = TTLCache<List<LiveDeparture>>(80)

    override fun covers(ride: LiveRide) = area.contains(ride.point)

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val net = if (ride.mode == "FERRY") "nav" else "aut"
        val stops = stopLists.value(net, 86_400) {
            JSONArray(LiveHTTP.text("https://oraritemporeale.actv.it/$net/backend/page/stops")).let { a ->
                J.objs(a).mapNotNull { s -> val id = J.str(s, "name"); val la = s.dbl("latitude"); val lo = s.dbl("longitude")
                    if (id != null && la != null && lo != null) Stop(id, LatLon(la, lo)) else null }
            }
        }
        val stop = stops.minByOrNull { it.point.distanceTo(ride.point) }?.takeIf { it.point.distanceTo(ride.point) < 150 } ?: return null
        val deps = boards.value(stop.id, 30) {
            val raw = JSONArray(LiveHTTP.text("https://oraritemporeale.actv.it/$net/backend/passages/" + java.net.URLEncoder.encode(stop.id, "UTF-8").replace("+", "%20")))
            J.objs(raw).mapNotNull { p ->
                val line = J.str(p, "line") ?: return@mapNotNull null
                val shown = J.str(p, "time") ?: ""
                val first = J.objs(p.optJSONArray("timingPoints")).firstOrNull()?.let { J.str(it, "time") }
                val scheduled = first?.let { clock(it, now) }
                val live = if (p.optBoolean("real")) (countdown(shown, now) ?: clock(shown, now)) else null
                if (scheduled == null && live == null) null else LiveDeparture(listOf(line), J.str(p, "destination"), scheduled, live)
            }
        }
        return LiveMatch.best(ride, deps, 150)?.let { LiveMatch.fix(it, name) }
    }

    /** "6'" → in 6 minutes. */
    private fun countdown(s: String, now: Instant) = s.trim().takeIf { it.endsWith("'") }?.dropLast(1)?.toIntOrNull()?.let { now.plusSeconds(it * 60L) }

    /** "23:55" → the nearest such time around now in Venice. */
    private fun clock(s: String, now: Instant): Instant? {
        val p = s.split(":").mapNotNull { it.toIntOrNull() }
        if (p.size != 2 || p[0] !in 0..23 || p[1] !in 0..59) return null
        val today = now.atZone(ZoneId.of(TZ)).withHour(p[0]).withMinute(p[1]).withSecond(0).withNano(0).toInstant()
        return when { today.epochSecond - now.epochSecond > 12 * 3600 -> today.minusSeconds(86_400); now.epochSecond - today.epochSecond > 12 * 3600 -> today.plusSeconds(86_400); else -> today }
    }
}

/** Storstockholms Lokaltrafik's open departures API (no key), with deviations. */
object SLSource : LiveSource {
    override val name = "SL"
    override val horizon = 60 * 60L
    private val area = GeoBox(58.7, 60.3, 17.2, 19.3)
    private data class Site(val id: Int, val point: LatLon)
    private val sites = TTLCache<List<Site>>(2)
    private val boards = TTLCache<List<LiveDeparture>>(80)

    override fun covers(ride: LiveRide) = area.contains(ride.point)

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val all = sites.value("sites", 7 * 86_400) {
            J.objs(JSONArray(LiveHTTP.text("https://transport.integration.sl.se/v1/sites?expand=false"))).mapNotNull { s ->
                val id = J.int(s, "id"); val la = s.dbl("lat"); val lo = s.dbl("lon")
                if (id != null && la != null && lo != null) Site(id, LatLon(la, lo)) else null
            }
        }
        val site = all.minByOrNull { it.point.distanceTo(ride.point) }?.takeIf { it.point.distanceTo(ride.point) < 250 } ?: return null
        val deps = boards.value(site.id.toString(), 30) { parse(JSONObject(LiveHTTP.text("https://transport.integration.sl.se/v1/sites/${site.id}/departures"))) }
        return LiveMatch.best(ride, deps)?.let { LiveMatch.fix(it, name) }
    }

    private fun deviations(a: JSONArray?) = J.objs(a).mapNotNull { d ->
        val text = J.str(d, "message")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        alertWith(text, "", if ((J.int(d, "importance_level") ?: 0) >= 7) "SEVERE" else "WARNING")
    }

    private fun parse(root: JSONObject): List<LiveDeparture> {
        val stopWide = J.objs(root.optJSONArray("stop_deviations")).mapNotNull { d ->
            val a = deviations(JSONArray().put(d)).firstOrNull() ?: return@mapNotNull null
            J.objs(d.optJSONObject("scope")?.optJSONArray("lines")).mapNotNull { J.str(it, "designation") } to a
        }
        return J.objs(root.optJSONArray("departures")).mapNotNull { d ->
            val designation = J.str(d.optJSONObject("line"), "designation") ?: return@mapNotNull null
            val scheduled = lt(J.str(d, "scheduled"), "yyyy-MM-dd'T'HH:mm:ss", "Europe/Stockholm") ?: return@mapNotNull null
            val state = J.str(d, "state") ?: ""
            val alerts = deviations(d.optJSONArray("deviations")) + stopWide.filter { it.first.isEmpty() || designation in it.first }.map { it.second }
            LiveDeparture(listOf(designation), J.str(d, "destination"), scheduled,
                if (state == "NOTEXPECTED") null else lt(J.str(d, "expected"), "yyyy-MM-dd'T'HH:mm:ss", "Europe/Stockholm"),
                state == "CANCELLED", J.str(d.optJSONObject("stop_point"), "designation"), alerts)
        }
    }
}

/** Transport for London line status (Powered by TfL Open Data): Underground, Overground, DLR, Elizabeth line, trams. */
object TfLStatusSource : LiveSource {
    override val name = "TfL"
    override val horizon = 4 * 3600L
    private val area = GeoBox(51.28, 51.70, -0.52, 0.34)
    private data class Line(val names: List<String>, val alerts: List<Alert>)
    private val status = TTLCache<List<Line>>(2)

    override fun covers(ride: LiveRide) = area.contains(ride.point)

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        if (ride.mode == "BUS" || ride.mode == "COACH") return null
        val lines = status.value("status", 90) { parse(JSONArray(LiveHTTP.text("https://api.tfl.gov.uk/Line/Mode/tube,overground,dlr,elizabeth-line,tram/Status"))) }
        val line = lines.firstOrNull { LiveMatch.sameLine(ride.lines, it.names) }?.takeIf { it.alerts.isNotEmpty() } ?: return null
        return LiveFix(alerts = line.alerts, source = name)
    }

    private fun parse(a: JSONArray) = J.objs(a).mapNotNull { l ->
        val name = J.str(l, "name") ?: return@mapNotNull null
        val alerts = J.objs(l.optJSONArray("lineStatuses")).mapNotNull { s ->
            val sev = J.int(s, "statusSeverity") ?: 10
            val reason = J.str(s, "reason")?.takeIf { it.isNotEmpty() }
            if (sev in listOf(10, 18, 19) || reason == null) return@mapNotNull null
            val periods = J.objs(s.optJSONArray("validityPeriods")).map { io.github.elija7580.publit.data.AlertPeriod(iso(J.str(it, "fromDate")), iso(J.str(it, "toDate"))) }
            val effect = when { sev in listOf(1, 2, 16, 20) -> "NO_SERVICE"; sev == 15 -> "DETOUR"; sev == 6 -> "SIGNIFICANT_DELAYS"; sev in listOf(3, 4, 5, 7, 11, 14) -> "REDUCED_SERVICE"; else -> null }
            alertWith("$name: ${J.str(s, "statusSeverityDescription") ?: ""}", reason.trim(), if (sev <= 6 || sev == 20) "SEVERE" else "WARNING",
                "https://tfl.gov.uk/tube-dlr-overground/status/", effect, periods)
        }
        Line(listOf(name, J.str(l, "id") ?: name), alerts)
    }
}

/** search.ch's timetable API (no key): live delays, cancellations and track changes in Switzerland. */
object SearchChSource : LiveSource {
    override val name = "search.ch"
    override val horizon = 3 * 3600L
    override val onlyWithoutLive = true
    private val area = GeoBox(45.81, 47.81, 5.95, 10.50)
    private val zones = setOf("Europe/Zurich", "Europe/Vaduz", "Europe/Busingen")
    private const val TZ = "Europe/Zurich"
    private const val API = "https://timetable.search.ch/api"
    private val stops = TTLCache<String>(400)
    private val boards = TTLCache<List<LiveDeparture>>(120)

    override fun covers(ride: LiveRide): Boolean {
        if (ride.stopId?.let(::stopNumber) != null) return true
        return ride.tz != null && ride.tz in zones && area.contains(ride.point)
    }

    /** "…_ch:1:sloid:87349::1" → "8587349". */
    fun stopNumber(stopId: String): String? {
        val i = stopId.indexOf("ch:1:sloid:"); if (i < 0) return null
        val d = stopId.substring(i + 11).takeWhile { it.isDigit() }
        if (d.isEmpty() || d.length > 5 || (d.toIntOrNull() ?: 0) <= 0) return null
        return "85" + "0".repeat(5 - d.length) + d
    }

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val p = ride.point
        val id = ride.stopId?.let(::stopNumber) ?: stops.value(LatLon.format(p.lat, 4) + "," + LatLon.format(p.lon, 4), 86_400) {
            J.objs(JSONArray(LiveHTTP.text("$API/completion.json?latlon=" + LatLon.format(p.lat, 6) + "," + LatLon.format(p.lon, 6) + "&accuracy=10&show_ids=1")))
                .filter { !J.str(it, "id").isNullOrEmpty() && (it.dbl("dist") ?: Double.MAX_VALUE) <= 100 }
                .minByOrNull { it.dbl("dist") ?: Double.MAX_VALUE }?.let { J.str(it, "id") } ?: ""
        }
        if (id.isEmpty()) return null
        val start = Instant.ofEpochSecond(ride.scheduled.epochSecond / 1800 * 1800 - 120)
        val z = start.atZone(ZoneId.of(TZ))
        val deps = boards.value("$id|${start.epochSecond}", 30) {
            val url = "$API/stationboard.json?stop=" + java.net.URLEncoder.encode(id, "UTF-8") + "&date=" + z.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) +
                "&time=" + z.format(DateTimeFormatter.ofPattern("HH:mm")) + "&limit=150&show_delays=1&show_tracks=1&show_trackchanges=1"
            J.objs(JSONObject(LiveHTTP.text(url)).optJSONArray("connections")).mapNotNull { c ->
                val scheduled = lt(J.str(c, "time"), "yyyy-MM-dd HH:mm:ss", TZ) ?: return@mapNotNull null
                val number = J.str(c, "*Z")?.trimStart('0')
                val category = J.str(c, "*G")
                val lines = listOfNotNull(J.str(c, "line"), number, if (category != null && number != null) "$category $number" else null).map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@mapNotNull null
                val delay = J.str(c, "dep_delay")?.trim()
                val minutes = delay?.replace("+", "")?.toIntOrNull()
                LiveDeparture(lines, J.str(c.optJSONObject("terminal"), "name"), scheduled, minutes?.let { scheduled.plusSeconds(it * 60L) },
                    delay?.uppercase() == "X", J.str(c, "track")?.replace("!", ""))
            }
        }
        return LiveMatch.best(ride, deps)?.let { LiveMatch.fix(it, name) }
    }
}

/** A German transport association's EFA departure monitor (rapidJSON, no key), matched by DHID. */
class EFASource(override val name: String, private val endpoint: String, private val regions: List<String>) : LiveSource {
    override val horizon = 2 * 3600L
    override val onlyWithoutLive = true

    companion object {
        private const val TZ = "Europe/Berlin"
        private val boards = TTLCache<List<LiveDeparture>>(160)
        val vrr = EFASource("VRR", "https://efa.vrr.de/standard/XML_DM_REQUEST", listOf("de:05"))
        val vrn = EFASource("VRN", "https://www.vrn.de/mngvrn/XML_DM_REQUEST", listOf("de:07"))
    }

    override fun covers(ride: LiveRide): Boolean {
        if (ride.tz != null && ride.tz != TZ) return false
        val id = ride.stopId?.let(::dhid) ?: return false
        return regions.any { id.startsWith(it) }
    }

    override suspend fun fix(ride: LiveRide, now: Instant): LiveFix? {
        val id = ride.stopId?.let(::dhid) ?: return null
        val start = Instant.ofEpochSecond(ride.scheduled.epochSecond / 1800 * 1800 - 120)
        val z = start.atZone(ZoneId.of(TZ))
        val deps = boards.value("$name|$id|${start.epochSecond}", 30) {
            val url = "$endpoint?outputFormat=rapidJSON&type_dm=stop&name_dm=" + java.net.URLEncoder.encode(id, "UTF-8") +
                "&mode=direct&useRealtime=1&depType=stopEvents&itdDate=" + z.format(DateTimeFormatter.ofPattern("yyyyMMdd")) +
                "&itdTime=" + z.format(DateTimeFormatter.ofPattern("HHmm")) + "&limit=120"
            J.objs(JSONObject(LiveHTTP.text(url)).optJSONArray("stopEvents")).mapNotNull { e ->
                val scheduled = iso(J.str(e, "departureTimeBaseTimetable") ?: J.str(e, "departureTimePlanned")) ?: return@mapNotNull null
                val t = e.optJSONObject("transportation")
                val lines = listOfNotNull(J.str(t, "number"), J.str(t, "disassembledName"), J.str(t, "name")).map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.isEmpty()) return@mapNotNull null
                val status = J.strs(e.optJSONArray("realtimeStatus"))
                val cancelled = e.optBoolean("isCancelled") || "TRIP_CANCELLED" in status || "STOP_CANCELLED" in status
                val monitored = e.optBoolean("isRealtimeControlled") || "MONITORED" in status
                val props = e.optJSONObject("location")?.optJSONObject("properties")
                val alerts = J.objs(e.optJSONArray("infos")).mapNotNull { info ->
                    val head = J.plain(J.str(info, "title") ?: J.str(info, "subtitle") ?: ""); val text = J.plain(J.str(info, "content") ?: "")
                    if (head.isEmpty() && text.isEmpty()) null
                    else alertWith(head, text, if ((J.str(info, "priority") ?: "").lowercase() in listOf("veryhigh", "high")) "SEVERE" else "WARNING")
                }
                LiveDeparture(lines, J.str(t?.optJSONObject("destination"), "name"), scheduled, if (monitored) iso(J.str(e, "departureTimeEstimated")) else null,
                    cancelled, J.str(props, "platformName") ?: J.str(props, "platform"), alerts)
            }
        }
        return LiveMatch.best(ride, deps)?.let { LiveMatch.fix(it, name) }
    }
}
