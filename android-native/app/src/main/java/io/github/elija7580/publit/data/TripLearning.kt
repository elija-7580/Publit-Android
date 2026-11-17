package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * "Learn from my trips" (port of iOS `TripLearning.swift`), off by default, on the device only.
 * During a started trip the recorder derives per change the real walk, made or missed, the spare time
 * and both delays; location points are never stored. From these a personal walking model is
 * estimated (Bayesian, from [WalkModel.PRIOR]); changes made often also use their own success rate.
 */
object TripLearning {
    const val MAX = 300
    @Volatile var file: File? = null
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: List<Observation>? = null

    val isEnabled get() = SmartRouting.prefs?.getBoolean(SmartRouting.LEARN_TRIPS, false) ?: false

    @Serializable
    data class Observation(val dateEpoch: Long, val key: String, val walk: TransferWalk, val walkSeconds: Double? = null,
                           val made: Boolean, val spareSeconds: Double? = null, val feederDelay: Double = 0.0, val connectionDelay: Double = 0.0)

    fun key(c: SmartRouting.Change): String {
        val zone = c.incoming.to.tz?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        return "${c.incoming.to.stationId}|${c.outgoing.routeId ?: c.outgoing.lineName}|${DelayHistory.window(c.outgoing.scheduledStartTime ?: c.outgoing.startTime, zone)}"
    }

    /** walk = factor·planned + perLevel·levels + perElevator·lifts + platform + station + big station + overhead. */
    fun walkModel(observations: List<Observation>, prior: WalkModel = WalkModel.PRIOR): WalkModel {
        val measured = observations.mapNotNull { o -> o.walkSeconds?.takeIf { it > 0 && it < 3600 }?.let { WalkModel.features(o.walk) to it } }
        if (measured.isEmpty()) return prior
        val n = 7
        val mu0 = prior.parameters
        val precision = DoubleArray(49); val b = DoubleArray(n)
        for (i in 0 until n) { val p = 1 / prior.covariance[i * 8]; precision[i * 8] = p; b[i] = p * mu0[i] }
        for ((x, y) in measured) {
            val expected = maxOf(30.0, mu0.zip(x).sumOf { (a, c) -> a * c })
            val v = (0.25 * expected + 30).pow(2)
            for (i in 0 until n) { b[i] += x[i] * y / v; for (j in 0 until n) precision[i * n + j] += x[i] * x[j] / v }
        }
        val cov = invert(precision, n) ?: return prior
        val bounds = listOf(0.6..2.5, 0.0..120.0, 0.0..300.0, 0.0..300.0, 0.0..300.0, 0.0..300.0, -60.0..240.0)
        val mu = List(n) { i -> (0 until n).sumOf { cov[i * n + it] * b[it] }.coerceIn(bounds[i]) }
        var sumSq = 0.0
        for ((x, y) in measured) { val e = maxOf(30.0, mu.zip(x).sumOf { (a, c) -> a * c }); sumSq += ln(y / e).pow(2) }
        val k0 = 5.0
        val sigma = sqrt((k0 * prior.sigma * prior.sigma + sumSq) / (k0 + measured.size)).coerceIn(0.05, 0.6)
        return WalkModel.of(mu, sigma, cov.toList(), measured.size)
    }

    /** Gauss–Jordan inverse of an n × n matrix (row-major); null if singular. */
    fun invert(m: DoubleArray, n: Int): DoubleArray? {
        val a = m.copyOf(); val inv = DoubleArray(n * n) { if (it % (n + 1) == 0) 1.0 else 0.0 }
        for (col in 0 until n) {
            val pivot = (col until n).maxByOrNull { abs(a[it * n + col]) } ?: return null
            if (abs(a[pivot * n + col]) < 1e-12) return null
            if (pivot != col) for (k in 0 until n) {
                var t = a[pivot * n + k]; a[pivot * n + k] = a[col * n + k]; a[col * n + k] = t
                t = inv[pivot * n + k]; inv[pivot * n + k] = inv[col * n + k]; inv[col * n + k] = t
            }
            val d = a[col * n + col]
            for (k in 0 until n) { a[col * n + k] /= d; inv[col * n + k] /= d }
            for (r in 0 until n) if (r != col) {
                val f = a[r * n + col]
                if (f != 0.0) for (k in 0 until n) { a[r * n + k] -= f * a[col * n + k]; inv[r * n + k] -= f * inv[col * n + k] }
            }
        }
        return inv
    }

    /** Made at least 3 times: the chance moved towards the own success rate (Beta prior worth five changes). */
    fun adjusted(chance: Double, key: String, observations: List<Observation>): Double {
        val same = observations.filter { it.key == key }
        if (same.size < 3) return chance
        val made = same.count { it.made }.toDouble(); val k = 5.0
        return ((k * chance + made) / (k + same.size)).coerceIn(0.01, 0.99)
    }

    @Synchronized fun observations(): List<Observation> {
        cache?.let { return it }
        val list = runCatching { json.decodeFromString(ListSerializer(Observation.serializer()), file!!.readText()) }.getOrDefault(emptyList())
        cache = list
        return list
    }

    @Synchronized fun add(new: List<Observation>) {
        if (!isEnabled || new.isEmpty()) return
        val list = (observations() + new).takeLast(MAX)
        cache = list
        file?.let { f -> runCatching { f.writeText(json.encodeToString(ListSerializer(Observation.serializer()), list)) } }
        WalkModel.store(SmartRouting.prefs, walkModel(list))
    }

    @Synchronized fun reset() {
        cache = emptyList()
        file?.delete()
        WalkModel.store(SmartRouting.prefs, null)
    }

    /** Follows one started trip through fixes; holds only a few timestamps per change, no track. */
    class Recorder {
        val reached = HashMap<Int, Instant>()
        private val done = HashSet<Int>()

        fun observe(here: LatLon, accuracy: Double, time: Instant, itinerary: Itinerary): List<Observation> {
            if (accuracy < 0 || accuracy > MAX_ACCURACY) return emptyList()
            val out = ArrayList<Observation>()
            SmartRouting.changes(itinerary, time).forEachIndexed { i, c ->
                if (i in done) return@forEachIndexed
                if (c.source == SmartRouting.Change.Source.ThroughRun) { done += i; return@forEachIndexed }
                val d = here.distanceTo(c.outgoing.from.coordinate)
                if (reached[i] == null && time >= c.incoming.endTime.minusSeconds(60) && d <= AT_STOP) reached[i] = time
                val left = c.outgoing.startTime
                val onBoard = time > left.plusSeconds(60) && d > 300 && near(here, c.outgoing, 150.0)
                val stuck = time > left.plusSeconds(300) && d <= 150
                if (!onBoard && !stuck) return@forEachIndexed
                done += i
                val r = reached[i]
                val walk = r?.let { (it.epochSecond - c.incoming.endTime.epochSecond).toDouble() }?.takeIf { it > 0 }
                out += Observation(time.epochSecond, key(c), c.walk ?: TransferWalk(c.walkSeconds.toDouble()), walk, onBoard,
                    r?.let { (left.epochSecond - it.epochSecond).toDouble() },
                    (c.incoming.endTime.epochSecond - c.incoming.scheduledEnd.epochSecond).toDouble(),
                    (c.outgoing.startTime.epochSecond - (c.outgoing.scheduledStartTime ?: c.outgoing.startTime).epochSecond).toDouble())
            }
            return out
        }

        companion object {
            const val AT_STOP = 40.0
            const val MAX_ACCURACY = 35.0

            /** Within [metres] of the leg's path (distance to each segment, flat-earth approximation). */
            fun near(p: LatLon, leg: Leg, metres: Double): Boolean {
                val path = leg.mapPath
                if (path.isEmpty()) return true
                if (path.size == 1) return path[0].distanceTo(p) <= metres
                val k = cos(Math.toRadians(p.lat)) * 111_320.0
                for (s in 0 until path.size - 1) {
                    val ax = (path[s].lon - p.lon) * k; val ay = (path[s].lat - p.lat) * 110_540
                    val bx = (path[s + 1].lon - p.lon) * k; val by = (path[s + 1].lat - p.lat) * 110_540
                    val dx = bx - ax; val dy = by - ay; val len = dx * dx + dy * dy
                    val t = if (len > 0) (-(ax * dx + ay * dy) / len).coerceIn(0.0, 1.0) else 0.0
                    val cx = ax + t * dx; val cy = ay + t * dy
                    if (sqrt(cx * cx + cy * cy) <= metres) return true
                }
                return false
            }
        }
    }
}

/** When to warn about a change (port of iOS `ChangeWatch.swift`): once per change, again only when clearly worse. */
object ChangeWatch {
    const val ENABLED = "smartChangeAlerts"
    const val THRESHOLD = 0.7
    const val WORSE = 0.15
    private const val WARNED = "changeWatchWarned"

    val isEnabled get() = SmartRouting.prefs?.getBoolean(ENABLED, true) ?: true

    fun key(c: SmartRouting.Change) =
        "${(c.outgoing.scheduledStartTime ?: c.outgoing.startTime).epochSecond}|${c.incoming.to.stationId}|${c.outgoing.tripId ?: c.outgoing.lineName}"

    fun isCancelled(c: SmartRouting.Change) = c.outgoing.cancelled == true || c.outgoing.from.cancelled == true || c.incoming.cancelled == true

    fun due(changes: List<SmartRouting.Change>, warned: Map<String, Double>, now: Instant) = changes.filter { c ->
        if (c.source == SmartRouting.Change.Source.ThroughRun || c.outgoing.startTime <= now) return@filter false
        if (!isCancelled(c) && c.chance >= THRESHOLD) return@filter false
        val last = warned[key(c)] ?: return@filter true
        c.chance <= last - WORSE
    }

    /** On foot between the vehicles: the chance from here at this pace (m/s). Null outside the walk. */
    fun onFoot(c: SmartRouting.Change, here: LatLon, now: Instant, speed: Double, table: DelayHistory.Table?, model: WalkModel = WalkModel.current()): Double? {
        if (now < c.incoming.endTime.minusSeconds(60) || now >= c.outgoing.startTime.plusSeconds(120) || speed <= 0.2) return null
        val seconds = here.distanceTo(c.outgoing.from.coordinate) * 1.3 / speed
        val zone = c.outgoing.from.tz?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() } ?: java.time.ZoneId.systemDefault()
        val sched = c.outgoing.scheduledStartTime ?: c.outgoing.startTime
        val dep = SmartRouting.delay(c.outgoing, false, now, table, zone)?.first
            ?: DelayHistory.Dist.PUNCTUAL.shifted((c.outgoing.startTime.epochSecond - sched.epochSecond).toDouble())
        val walk = DoubleArray(WalkModel.Z.size) { seconds * kotlin.math.exp(model.sigma * WalkModel.Z[it]) }
        return SmartRouting.onTime((sched.epochSecond - now.epochSecond).toDouble(), DelayHistory.Dist.PUNCTUAL, dep, walk)
    }

    fun alternatives(c: SmartRouting.Change) = c.outgoing.alternatives.orEmpty().count { alt ->
        alt.firstOrNull { it.isTransit }?.let { it.startTime > c.outgoing.startTime.plusSeconds(59) && it.cancelled != true } ?: false
    }

    fun message(c: SmartRouting.Change): String {
        if (isCancelled(c)) return io.github.elija7580.publit.core.L10n.f("Your connection at %@ is cancelled – tap for other routes", c.stopName)
        val n = alternatives(c)
        return if (n > 0) io.github.elija7580.publit.core.L10n.f("Your connection at %@ is unlikely – %lld alternatives", c.stopName, n)
        else io.github.elija7580.publit.core.L10n.f("Your connection at %@ is unlikely – tap for other routes", c.stopName)
    }

    fun warned(): Map<String, Double> = SmartRouting.prefs?.getStringSet(WARNED, emptySet()).orEmpty()
        .mapNotNull { e -> e.substringAfterLast('=').toDoubleOrNull()?.let { e.substringBeforeLast('=') to it } }.toMap()

    fun markWarned(c: SmartRouting.Change) {
        val w = warned().toMutableMap()
        w[key(c)] = if (isCancelled(c)) 0.0 else c.chance
        val kept = w.entries.sortedBy { it.key }.takeLast(100).map { "${it.key}=${it.value}" }.toSet()
        SmartRouting.prefs?.edit()?.putStringSet(WARNED, kept)?.apply()
    }
}
