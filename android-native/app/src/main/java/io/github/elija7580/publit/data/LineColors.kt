package io.github.elija7580.publit.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** GTFS line colours with a legibility check (WCAG 2 contrast). */
object LineColors {
    data class Pair(val background: String, val text: String)

    data class RGB(val r: Double, val g: Double, val b: Double) {
        val luminance: Double
            get() {
                fun lin(c: Double) = if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
                return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
            }
        val hex: String
            get() {
                fun byte(v: Double) = max(0, min(255, Math.round(v * 255).toInt()))
                return String.format(java.util.Locale.ROOT, "%02X%02X%02X", byte(r), byte(g), byte(b))
            }

        companion object {
            /** Parses `e3000f` or `#E3000F`. */
            fun parse(hex: String?): RGB? {
                var s = hex?.trim() ?: return null
                if (s.startsWith("#")) s = s.drop(1)
                if (s.length != 6) return null
                val v = s.toLongOrNull(16) ?: return null
                return RGB(((v shr 16) and 0xFF) / 255.0, ((v shr 8) and 0xFF) / 255.0, (v and 0xFF) / 255.0)
            }
        }
    }

    fun contrast(a: RGB, b: RGB): Double {
        val l1 = max(a.luminance, b.luminance)
        val l2 = min(a.luminance, b.luminance)
        return (l1 + 0.05) / (l2 + 0.05)
    }

    private val white = RGB(1.0, 1.0, 1.0)
    private val black = RGB(0.0, 0.0, 0.0)
    const val MINIMUM_OPERATOR_CONTRAST = 4.5

    fun normalized(hex: String) = hex.trim().replace("#", "").uppercase()

    /** Keeps the operator's text colour when legible, otherwise white or black. */
    fun resolve(routeColor: String?, routeTextColor: String?, fallback: String): Pair {
        val bgHex = if (RGB.parse(routeColor) != null) normalized(routeColor!!) else normalized(fallback)
        val bg = RGB.parse(bgHex) ?: return Pair("4D4D59", "FFFFFF")
        val given = RGB.parse(routeTextColor)
        if (routeColor != null && given != null && contrast(given, bg) >= MINIMUM_OPERATOR_CONTRAST) {
            return Pair(bgHex, normalized(routeTextColor!!))
        }
        return Pair(bgHex, if (contrast(white, bg) >= contrast(black, bg)) "FFFFFF" else "000000")
    }

    private val darkSurface = RGB.parse("1C1C1E")!!
    private val lightSurface = RGB.parse("FFFFFF")!!
    const val MINIMUM_SURFACE_CONTRAST = 1.8

    /** Near-black badges on dark backgrounds (and white ones on light) need a visible edge. */
    fun blendsIn(hex: String?, dark: Boolean): Boolean {
        val c = RGB.parse(hex) ?: return false
        return contrast(c, if (dark) darkSurface else lightSurface) < MINIMUM_SURFACE_CONTRAST
    }
}
