// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import kotlin.math.cos

/**
 * A ride's line without the server's detours (iOS `LegShape`, Publit#114). Transitous draws a ride along the
 * tracks of OpenStreetMap; around large stations that line can leave the route between two stops in a row, run
 * along other tracks and come back (Paris, checked on 2026-11-27: RER A from Châtelet to Auber, 1.7 km apart,
 * came with 14 km of line reaching 5 km away; métro 5 and 7 around Gare de l'Est likewise). The map then drew
 * branches the ride never takes. Pure logic.
 */
object LegShape {
    /** Stops closer than this prove nothing (platforms of one station, a loop back to its start). */
    const val MIN_STOP_GAP = 150.0

    /**
     * The line between two stops in a row counts as wrong when it is this many times as long as the straight way
     * and at least [MIN_EXTRA] metres longer. Real tracks stay below: the spirals of the Albula line between
     * Bergün and Preda are 12.6 km for 4 km (3.1).
     */
    const val MAX_RATIO = 3.5
    const val MIN_EXTRA = 1_000.0

    /** Rail only: a road with hairpins or a ferry around a headland can be several times the straight way. */
    val railBound: Set<String> = setOf("HIGHSPEED_RAIL", "LONG_DISTANCE", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL",
        "NIGHT_RAIL", "RAIL", "SUBURBAN", "SUBWAY", "METRO", "TRAM")

    fun length(path: List<LatLon>): Double {
        var sum = 0.0
        for (i in 1 until path.size) sum += path[i - 1].distanceTo(path[i])
        return sum
    }

    /**
     * [path] with each such detour replaced by the straight way between its two stops; null when there is none
     * (the usual case, decided by adding up the path once).
     */
    fun withoutDetours(path: List<LatLon>, stops: List<LatLon>): List<LatLon>? {
        if (path.size < 3 || stops.size < 2) return null
        // No detour of MIN_EXTRA fits into a line about as long as the way from stop to stop.
        if (length(path) - length(stops) < MIN_EXTRA) return null
        // Where each stop lies on the line: the closest point at or after the stop before it.
        val at = IntArray(stops.size)
        var start = 0
        for ((k, stop) in stops.withIndex()) {
            val kx = cos(Math.toRadians(stop.lat))
            var best = start
            var bestDistance = Double.MAX_VALUE
            for (i in start until path.size) {
                val dx = (path[i].lon - stop.lon) * kx
                val dy = path[i].lat - stop.lat
                val d = dx * dx + dy * dy
                if (d < bestDistance) { best = i; bestDistance = d }
            }
            at[k] = best
            start = best
        }
        val out = ArrayList<LatLon>(path.size)
        out.addAll(path.subList(0, at[0] + 1))
        var changed = false
        for (k in 0 until stops.size - 1) {
            val i = at[k]
            val j = at[k + 1]
            if (j <= i) continue
            val straight = stops[k].distanceTo(stops[k + 1])
            val piece = length(path.subList(i, j + 1))
            if (straight >= MIN_STOP_GAP && piece >= MAX_RATIO * straight && piece - straight >= MIN_EXTRA) {
                out.add(path[j])
                changed = true
            } else {
                out.addAll(path.subList(i + 1, j + 1))
            }
        }
        out.addAll(path.subList(at[at.size - 1] + 1, path.size))
        return if (changed) out else null
    }

    /** This ride with its line tidied, also in the rides that can replace it. */
    fun tidy(leg: Leg): Leg {
        var out = leg
        val geometry = leg.legGeometry
        if (geometry != null && leg.mode in railBound) {
            withoutDetours(Polyline.decode(geometry.points, geometry.precision), leg.allStops.map { it.coordinate })?.let { path ->
                out = out.copy(legGeometry = EncodedPolyline(Polyline.encode(path, geometry.precision), geometry.precision, path.size))
            }
        }
        val alternatives = leg.alternatives
        if (alternatives != null) out = out.copy(alternatives = alternatives.map { option -> option.map { ride -> tidy(ride) } })
        return out
    }

    /** This route with the line of every ride tidied. */
    fun tidy(route: Itinerary): Itinerary = route.copy(legs = route.legs.map { ride -> tidy(ride) })
}
