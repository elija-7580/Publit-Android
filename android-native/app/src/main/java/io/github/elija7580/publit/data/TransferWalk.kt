package io.github.elija7580.publit.data

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The walk of a change (port of iOS `TransferWalk.swift`): what makes it longer than the router's
 * walking time – lifts, levels, a platform that changed after planning, a station change, big stations.
 */
@Serializable
data class TransferWalk(
    val plannedSeconds: Double,
    val levels: Double = 0.0,
    val elevators: Int = 0,
    val platformChanged: Boolean = false,
    val stationChange: Boolean = false,
    val bigStation: Boolean = false,
) {
    companion object {
        fun of(incoming: Leg, walks: List<Leg>, outgoing: Leg): TransferWalk {
            var floors = 0.0; var lifts = 0
            val seen = HashSet<Double>()
            for (s in walks.flatMap { it.steps.orEmpty() }) {
                val a = s.fromLevel; val b = s.toLevel
                if (a != null && b != null) { floors += abs(b - a); seen += a; seen += b }
                if (s.relativeDirection == "ELEVATOR") lifts++
            }
            fun moved(p: Place) = p.track != null && p.scheduledTrack != null && p.track != p.scheduledTrack
            return TransferWalk(walks.sumOf { it.duration }.toDouble(), floors, lifts, moved(incoming.to) || moved(outgoing.from),
                incoming.to.stationId != outgoing.from.stationId, seen.size >= 3)
        }
    }
}

/** How long changes take this user; "Learn from my trips" moves it (`TripLearning`). */
@Serializable
data class WalkModel(
    val factor: Double = 1.05,
    val perLevel: Double = 10.0,
    val perElevator: Double = 30.0,
    val platformChange: Double = 60.0,
    val stationChange: Double = 30.0,
    val bigStation: Double = 30.0,
    val overhead: Double = 0.0,
    val sigma: Double = 0.10,
    val covariance: List<Double> = PRIOR_COVARIANCE,
    val observations: Int = 0,
) {
    val parameters get() = listOf(factor, perLevel, perElevator, platformChange, stationChange, bigStation, overhead)

    fun expectedSeconds(w: TransferWalk): Double = parameters.zip(features(w)).sumOf { (a, b) -> a * b }

    /** Spread for this walk: the model's own plus how unsure the model still is (x' C x). */
    fun spread(w: TransferWalk): Double {
        val x = features(w); val mid = expectedSeconds(w)
        if (mid <= 0) return sigma
        var v = 0.0
        for (i in 0 until 7) for (j in 0 until 7) v += x[i] * covariance[i * 7 + j] * x[j]
        return sqrt(sigma * sigma + max(0.0, v) / (mid * mid))
    }

    fun samples(w: TransferWalk, extraSeconds: Double = 0.0): DoubleArray {
        val mid = max(0.0, expectedSeconds(w)); val s = spread(w)
        return DoubleArray(Z.size) { mid * exp(s * Z[it]) + extraSeconds }
    }

    companion object {
        val PRIOR_SD = listOf(0.20, 10.0, 30.0, 45.0, 45.0, 30.0, 15.0)
        val PRIOR_COVARIANCE: List<Double> = List(49) { if (it % 8 == 0) PRIOR_SD[it / 7].pow(2) else 0.0 }
        val PRIOR = WalkModel()
        val Z = doubleArrayOf(-1.465, -0.792, -0.366, 0.0, 0.366, 0.792, 1.465)
        const val KEY = "smartWalkModel"
        private val json = Json { ignoreUnknownKeys = true }

        fun features(w: TransferWalk) = listOf(w.plannedSeconds, w.levels, w.elevators.toDouble(),
            if (w.platformChanged) 1.0 else 0.0, if (w.stationChange) 1.0 else 0.0, if (w.bigStation) 1.0 else 0.0, 1.0)

        fun of(p: List<Double>, sigma: Double, covariance: List<Double>, observations: Int) =
            WalkModel(p[0], p[1], p[2], p[3], p[4], p[5], p[6], sigma, covariance, observations)

        /** The learned model when "Learn from my trips" is on and has one, else the general values. */
        fun current(prefs: SharedPreferences? = SmartRouting.prefs): WalkModel {
            val p = prefs ?: return PRIOR
            if (!p.getBoolean(SmartRouting.LEARN_TRIPS, false)) return PRIOR
            return p.getString(KEY, null)?.let { runCatching { json.decodeFromString<WalkModel>(it) }.getOrNull() }
                ?.takeIf { it.covariance.size == 49 } ?: PRIOR
        }

        fun store(prefs: SharedPreferences?, m: WalkModel?) {
            prefs?.edit()?.apply { if (m == null) remove(KEY) else putString(KEY, json.encodeToString(serializer(), m)) }?.apply()
        }
    }
}
