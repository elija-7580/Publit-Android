package io.github.elija7580.publit.data

import android.net.Uri
import io.github.elija7580.publit.core.LatLon
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * A route as a link (SharedRoute on iOS), the same format on iPhone and Android:
 * `publit://route?i=…&f=…&fl=…&t=…&tl=…&d=…`. `i` is the MOTIS itinerary ID, packed as on iOS
 * (`z` + base64url of raw DEFLATE, `b` + base64url, `s` + plain).
 */
data class SharedRoute(val itineraryId: String?, val fromName: String, val from: LatLon, val toName: String, val to: LatLon, val departure: Instant) {
    val query: String get() {
        val items = ArrayList<Pair<String, String>>()
        itineraryId?.let { items += "i" to ShareLinks.pack(it) }
        items += listOf("f" to fromName, "fl" to from.apiValue, "t" to toName, "tl" to to.apiValue, "d" to departure.epochSecond.toString())
        return items.joinToString("&") { (k, v) -> k + "=" + ShareLinks.encode(v) }
    }

    companion object {
        fun of(it: Itinerary, fromName: String, toName: String): SharedRoute {
            val start = it.legs.firstOrNull()?.from; val end = it.legs.lastOrNull()?.to
            return SharedRoute(it.id.ifEmpty { null }, fromName.ifEmpty { start?.name.orEmpty() }, LatLon(start?.lat ?: 0.0, start?.lon ?: 0.0),
                toName.ifEmpty { end?.name.orEmpty() }, LatLon(end?.lat ?: 0.0, end?.lon ?: 0.0), it.legs.firstOrNull()?.scheduledStartTime ?: it.startTime)
        }

        private fun latLon(s: String?): LatLon? = s?.split(",")?.takeIf { it.size == 2 }?.let { p ->
            val a = p[0].trim().toDoubleOrNull(); val b = p[1].trim().toDoubleOrNull()
            if (a != null && b != null && kotlin.math.abs(a) <= 90 && kotlin.math.abs(b) <= 180) LatLon(a, b) else null
        }

        fun parse(query: String): SharedRoute? {
            val v = HashMap<String, String>()
            query.split("&").forEach { pair ->
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) v[kv[0]] = Uri.decode(kv[1])
            }
            val from = latLon(v["fl"]) ?: return null
            val to = latLon(v["tl"]) ?: return null
            val d = v["d"]?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
            return SharedRoute(v["i"]?.let(ShareLinks::unpack), v["f"].orEmpty(), from, v["t"].orEmpty(), to, Instant.ofEpochSecond(d.toLong()))
        }
    }
}

/** Links for other people: `https://publit.eu/open/#route?…` (opens Publit on iPhone and Android, else maps). */
object ShareLinks {
    const val WEB_HOST = "publit.eu"
    private const val WEB_PATH = "/open/"
    private val legacyHosts = listOf("elija-7580.github.io")
    private val legacyPaths = listOf("/publit-site/open/", "/Publit-/open/", "/Publit/open/")

    fun web(appLink: String): String = "https://$WEB_HOST$WEB_PATH#" + appLink.removePrefix("publit://")
    fun route(r: SharedRoute) = web("publit://route?" + r.query)
    fun stop(id: String, name: String, at: LatLon) =
        web("publit://stop/" + encode(id) + "?name=" + encode(name) + "&lat=" + at.lat + "&lon=" + at.lon)

    /** The publit:// link inside a web share link. */
    fun appUrl(fromWeb: Uri): String? {
        if (fromWeb.scheme?.lowercase() != "https") return null
        val host = fromWeb.host?.lowercase() ?: return null
        if (host != WEB_HOST && host !in legacyHosts) return null
        val path = fromWeb.path ?: return null
        if ((listOf(WEB_PATH) + legacyPaths).none { path.startsWith(it.dropLast(1)) }) return null
        val fragment = fromWeb.encodedFragment?.takeIf { it.isNotEmpty() } ?: return null
        return "publit://$fragment"
    }

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    fun encode(s: String): String = buildString {
        s.toByteArray(Charsets.UTF_8).forEach { b ->
            val c = (b.toInt() and 0xFF)
            if (c < 128 && UNRESERVED.indexOf(c.toChar()) >= 0) append(c.toChar()) else append("%" + "%02X".format(c))
        }
    }

    fun pack(id: String): String {
        val data = runCatching { Base64.getDecoder().decode(id) }.getOrNull() ?: return "s$id"
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true).apply { setInput(data); finish() }
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        val z = out.toByteArray()
        return if (z.size < data.size) "z" + base64url(z) else "b" + base64url(data)
    }

    fun unpack(s: String): String? {
        if (s.isEmpty()) return null
        val body = s.substring(1)
        return when (s[0]) {
            's' -> body.ifEmpty { null }
            'b' -> fromBase64url(body)?.let { Base64.getEncoder().encodeToString(it) }
            'z' -> fromBase64url(body)?.let { z ->
                runCatching {
                    val inf = Inflater(true).apply { setInput(z) }
                    val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
                    while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break; out.write(buf, 0, n) }
                    inf.end()
                    Base64.getEncoder().encodeToString(out.toByteArray())
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun base64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    private fun fromBase64url(s: String) = runCatching { Base64.getUrlDecoder().decode(s) }.getOrNull()
}
