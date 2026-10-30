package io.github.elija7580.publit.tickets

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.elija7580.publit.BuildConfigInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A logo Publit can offer for a ticket's operator. */
data class LogoCandidate(val url: String, val title: String, val credit: String, val wikimedia: Boolean)

/**
 * Operator logos (OperatorLogos on iOS): the logo (P154) of the operator's Wikidata item as PNG from
 * Wikimedia Commons, then the website's icon. Only the operator's name or Wikidata ID is sent.
 */
object OperatorLogos {
    private const val API = "https://www.wikidata.org/w/api.php"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun commonsUrl(file: String, width: Int = 320) =
        "https://commons.wikimedia.org/wiki/Special:FilePath/" + enc(file.replace(' ', '_')).replace("+", "%20") + "?width=$width"

    fun websiteIconUrl(site: String): String? {
        val host = runCatching { URL(if (site.contains("://")) site else "https://$site").host }.getOrNull()?.lowercase() ?: return null
        val domain = host.removePrefix("www.")
        return if ("." in domain && " " !in domain) "https://www.google.com/s2/favicons?domain=${enc(domain)}&sz=256" else null
    }

    private data class Entity(val id: String, val label: String?, val logos: List<String>, val website: String?)

    private fun values(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull {
        a!!.optJSONObject(it)?.optJSONObject("mainsnak")?.optJSONObject("datavalue")?.opt("value") as? String
    }

    /** Current logos first: preferred rank, no end date, not a white or negative variant. */
    private fun logos(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val c = a!!.optJSONObject(i) ?: return@mapNotNull null
        val file = values(JSONArray().put(c)).firstOrNull() ?: return@mapNotNull null
        val rank = c.optString("rank", "normal")
        if (rank == "deprecated") return@mapNotNull null
        var score = 0
        if (rank == "preferred") score += 4
        if (c.optJSONObject("qualifiers")?.has("P582") == true) score -= 6
        val l = file.lowercase()
        if (listOf("white", "weiß", "weiss", "negativ", "negative", "inverted", "blanc", "bianco").any { it in l }) score -= 3
        Triple(file, score, i)
    }.sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third }).map { it.first }

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 15_000; setRequestProperty("User-Agent", BuildConfigInfo.userAgent)
        }
        try { if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}"); c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    /** Candidates for an operator: its logos (Wikidata), then its website icon. */
    suspend fun lookup(name: String, language: String): List<LogoCandidate> {
        val trimmed = name.trim()
        if (trimmed.length < 2) return emptyList()
        val known = KnownOperators.find(trimmed)
        var ids = listOfNotNull(known?.wikidata)
        if (ids.isEmpty()) ids = runCatching {
            val root = JSONObject(get("$API?action=wbsearchentities&search=${enc(trimmed)}&language=$language&uselang=$language&type=item&limit=5&format=json"))
            J_ids(root.optJSONArray("search")).take(4)
        }.getOrDefault(emptyList())
        val found = if (ids.isEmpty()) emptyMap() else runCatching {
            val langs = if (language == "en") "en" else "$language|en"
            val all = JSONObject(get("$API?action=wbgetentities&ids=${enc(ids.joinToString("|"))}&props=claims|labels&languages=${enc(langs)}&format=json")).optJSONObject("entities") ?: JSONObject()
            all.keys().asSequence().associateWith { id ->
                val e = all.getJSONObject(id)
                val labels = e.optJSONObject("labels")
                val label = labels?.optJSONObject(language)?.optString("value") ?: labels?.optJSONObject("en")?.optString("value")
                val claims = e.optJSONObject("claims")
                Entity(id, label, logos(claims?.optJSONArray("P154")), values(claims?.optJSONArray("P856")).firstOrNull())
            }
        }.getOrDefault(emptyMap())
        // Search hits without a logo or website are people, places or vehicles of the same name.
        val useful = ids.filter { id -> found[id]?.let { it.logos.isNotEmpty() || it.website != null } == true }
        val out = LinkedHashMap<String, LogoCandidate>()
        for (id in useful) { val e = found[id]!!; for (f in e.logos.take(3)) commonsUrl(f).let { out.putIfAbsent(it, LogoCandidate(it, e.label ?: id, "Wikimedia Commons: $f", true)) } }
        val sites = useful.mapNotNull { found[it]?.website } + listOfNotNull(known?.domain)
        for (site in sites.take(2)) websiteIconUrl(site)?.let { u ->
            val host = runCatching { URL(if (site.contains("://")) site else "https://$site").host }.getOrDefault(site)
            out.putIfAbsent(u, LogoCandidate(u, host, host, false))
        }
        return out.values.toList()
    }

    /** The logo as a small PNG (at most 2 MB downloaded, scaled to 256 px), or null. */
    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 15_000; setRequestProperty("User-Agent", BuildConfigInfo.userAgent) }
            val bytes = try { if (c.responseCode !in 200..299) return@runCatching null; c.inputStream.use { it.readBytes() } } finally { c.disconnect() }
            if (bytes.size > 2_000_000) return@runCatching null
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@runCatching null
            val long = maxOf(bmp.width, bmp.height)
            if (long > 256) { val f = 256f / long; bmp = Bitmap.createScaledBitmap(bmp, maxOf(1, (bmp.width * f).toInt()), maxOf(1, (bmp.height * f).toInt()), true) }
            java.io.ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        }.getOrNull()
    }

    private fun J_ids(a: JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it)?.optString("id")?.takeIf { s -> s.isNotEmpty() } }
}
