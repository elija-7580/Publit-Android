package io.github.elija7580.publit.core

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** Formatting as Fmt in the iOS app. */
object Fmt {
    /** "HH:mm" or "h:mm a", set from the device's 24-hour setting at start. */
    @Volatile var timePattern: String = "HH:mm"
        set(value) { field = value; formatters.clear() }

    private val formatters = ConcurrentHashMap<String, DateTimeFormatter>()

    fun duration(seconds: Int): String {
        val minutes = maxOf(1, (seconds / 60.0).roundToInt())
        if (minutes < 60) return L10n.f("%lld min", minutes)
        val h = minutes / 60
        val m = minutes % 60
        return if (m == 0) L10n.f("%lld h", h) else L10n.f("%lld h %lld min", h, m)
    }

    fun distance(meters: Double): String {
        // 999.6 m is "1.0 km", not "1000 m".
        if (Math.round(meters) < 1000) return "${maxOf(0.0, meters).roundToInt()} m"
        val f = NumberFormat.getNumberInstance(L10n.locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return f.format(meters / 1000) + " km"
    }

    fun zone(id: String?): ZoneId = id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

    /** Clock time in the stop's own time zone (a trip in Istanbul shows Istanbul time). */
    fun time(instant: Instant, tz: String? = null): String {
        val zone = zone(tz)
        val key = timePattern + "|" + zone.id + "|" + L10n.locale
        val f = formatters.getOrPut(key) { DateTimeFormatter.ofPattern(timePattern, L10n.locale).withZone(zone) }
        return f.format(instant)
    }
}
