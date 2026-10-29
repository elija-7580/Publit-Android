package io.github.elija7580.publit.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Encoded polylines (Google format, MOTIS precision 6), as Polyline.swift. */
object Polyline {
    fun decode(encoded: String, precision: Int): List<LatLon> {
        if (precision !in 0..7 || encoded.length > 1_000_000) return emptyList()
        val factor = 10.0.pow(precision)
        val out = ArrayList<LatLon>()
        var index = 0
        var lat = 0L; var lon = 0L
        fun next(): Long? {
            var bits = 0L; var shift = 0
            while (index < encoded.length && shift < 40) {
                val b = encoded[index++].code
                if (b < 63 || b > 126) return null
                val v = (b - 63).toLong()
                bits = bits or ((v and 31) shl shift)
                if (v < 32) {
                    val m = bits ushr 1
                    return if (bits and 1L == 1L) -m - 1 else m
                }
                shift += 5
            }
            return null
        }
        while (index < encoded.length) {
            if (out.size >= 50_000) return emptyList()
            val dLat = next() ?: return emptyList()
            val dLon = next() ?: return emptyList()
            lat += dLat; lon += dLon
            val p = LatLon(lat / factor, lon / factor)
            if (p.lat !in -90.0..90.0 || p.lon !in -180.0..180.0) return emptyList()
            out += p
        }
        return out
    }

    /** Douglas–Peucker: drops points closer than `tolerance` metres to the line. */
    fun simplify(points: List<LatLon>, toleranceMeters: Double = 4.0): List<LatLon> {
        if (points.size <= 2) return points
        val lat0 = points[0].lat * PI / 180
        val mx = 111_320 * cos(lat0); val my = 110_540.0
        val xs = points.map { it.lon * mx }; val ys = points.map { it.lat * my }
        val tol2 = toleranceMeters * toleranceMeters
        val keep = BooleanArray(points.size); keep[0] = true; keep[points.size - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>(); stack.addLast(0 to points.size - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b <= a + 1) continue
            val dx = xs[b] - xs[a]; val dy = ys[b] - ys[a]
            val len2 = dx * dx + dy * dy
            var worst = -1.0; var idx = a
            for (i in a + 1 until b) {
                val px = xs[i] - xs[a]; val py = ys[i] - ys[a]
                val d2 = if (len2 == 0.0) px * px + py * py else {
                    val t = max(0.0, min(1.0, (px * dx + py * dy) / len2))
                    val ex = px - t * dx; val ey = py - t * dy
                    ex * ex + ey * ey
                }
                if (d2 > worst) { worst = d2; idx = i }
            }
            if (worst > tol2) { keep[idx] = true; stack.addLast(a to idx); stack.addLast(idx to b) }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }
}
