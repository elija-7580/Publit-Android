package io.github.elija7580.publit

import android.content.Intent
import android.net.Uri
import io.github.elija7580.publit.core.LatLon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * A place shared from another app (Share/ShareViewController and DeepLink on iOS): Google Maps (also short
 * links), Apple Maps, OpenStreetMap, geo: and plain coordinates; publit://place and publit://go/home|work.
 */
object SharedPlace {
    data class Found(val name: String, val at: LatLon)

    private val patterns = listOf(
        Regex("""@(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
        Regex("""!3d(-?\d{1,2}\.\d+)!4d(-?\d{1,3}\.\d+)"""),
        Regex("""[?&](?:q|ll|sll|query|daddr|destination|center)=(-?\d{1,2}\.\d+),\s*(-?\d{1,3}\.\d+)"""),
        Regex("""mlat=(-?\d{1,2}\.\d+)&mlon=(-?\d{1,3}\.\d+)"""),
        Regex("""#map=\d+/(-?\d{1,2}\.\d+)/(-?\d{1,3}\.\d+)"""),
        Regex("""geo:(-?\d{1,2}\.\d+),(-?\d{1,3}\.\d+)"""),
        Regex("""^\s*(-?\d{1,2}\.\d{3,}),\s*(-?\d{1,3}\.\d{3,})\s*$"""),
    )

    /** The text or link of a share or view intent; null when the intent is not about a place. */
    fun text(i: Intent?): String? = when (i?.action) {
        Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> i.data?.takeIf { it.scheme == "geo" || it.scheme == "publit" && it.host == "place" }?.toString()
        else -> null
    }

    fun parse(text: String): Found? {
        val decoded = Uri.decode(text)
        val m = patterns.firstNotNullOfOrNull { it.find(decoded) } ?: return null
        val lat = m.groupValues[1].toDouble(); val lon = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val name = Regex("""/place/([^/@]+)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')
            ?: Regex("""[?&](?:name|q)=([^&\d][^&]*)""").find(decoded)?.groupValues?.get(1)?.replace('+', ' ')
            ?: Regex("""geo:[^?]*\?q=[^(]*\(([^)]+)\)""").find(decoded)?.groupValues?.get(1)
            ?: text.lineSequence().firstOrNull { it.isNotBlank() && !it.contains("://") }?.trim()?.take(60)
            ?: io.github.elija7580.publit.core.L10n.t("Place from the link")
        return Found(name, LatLon(lat, lon))
    }

    /** Follows a short link (maps.app.goo.gl, goo.gl/maps) to the full URL with coordinates. */
    suspend fun resolve(text: String): Found? {
        parse(text)?.let { return it }
        val url = Regex("""https?://\S+""").find(text)?.value ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                var u = url
                repeat(5) {
                    val c = URL(u).openConnection() as HttpURLConnection
                    c.instanceFollowRedirects = false; c.connectTimeout = 8000; c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "Publit Android")
                    val loc = c.getHeaderField("Location"); c.disconnect()
                    if (loc == null) return@runCatching null
                    u = loc
                    parse(u)?.let { f -> return@runCatching f }
                }
                null
            }.getOrNull()
        }
    }
}
