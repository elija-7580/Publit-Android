// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import kotlin.math.abs
import kotlin.math.cos

/**
 * "Front of the train · Exit B2" (ExitAdvice.swift on iOS): where to ride and which way out, worked out per trip
 * from where the walk after the ride leaves the platform or, at a change, where the next train leaves.
 * The exit numbers come from OpenStreetMap (`StationExits.load`); before they arrive only the part is said.
 */
data class ExitAdvice(val part: Part? = null, val door: String? = null, val forChange: Boolean = false) {
    enum class Part { FRONT, MIDDLE, BACK }

    val isEmpty get() = part == null && door == null

    /** "Front of the train", for a line that already names the ride. */
    val partText: String? get() = when (part) {
        Part.FRONT -> L10n.t("Front of the train"); Part.MIDDLE -> L10n.t("Middle of the train"); Part.BACK -> L10n.t("Back of the train"); null -> null
    }

    /** One short line: "Front of the train · Exit B2". */
    val short: String? get() = listOfNotNull(partText, door?.let { L10n.f("Exit %@", it) }).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    companion object {
        val stationModes = setOf("SUBWAY", "METRO", "HIGHSPEED_RAIL", "LONG_DISTANCE", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "NIGHT_RAIL", "RAIL", "SUBURBAN")
        const val END_BAND = 25.0
        const val MIDDLE_BAND = 10.0
        const val MAX_REACH = 400.0
        const val PLATFORM_REACH = 250.0

        fun applies(ride: Leg) = ride.isTransit && ride.mode in stationModes

        /** The advice for the ride at `index`, or null when there is nothing to say. */
        fun forRide(index: Int, legs: List<Leg>): ExitAdvice? {
            val ride = legs.getOrNull(index)?.takeIf(::applies) ?: return null
            var door: String? = null
            var forChange = false
            var target: LatLon? = null
            val walk = legs.getOrNull(index + 1)
            if (walk != null && !walk.isTransit) {
                val route = WalkRoute(walk)
                val exitManeuver = route.maneuvers.firstOrNull { it.exitRef != null }
                if (StationExits.leavesUnderground(legs, index + 1)) door = exitManeuver?.exitRef
                else forChange = legs.getOrNull(index + 2)?.isTransit == true
                target = leavesPlatform(route) ?: exitManeuver?.coordinate
                if (target == null && forChange) target = legs[index + 2].from.coordinate
            } else if (walk != null && walk.isTransit) {
                forChange = true
                target = walk.from.coordinate
            }
            val advice = ExitAdvice(target?.let { part(ride, it) }, door, forChange)
            return if (advice.isEmpty) null else advice
        }

        /** Where the walk leaves the platform: its first stairs, escalator or lift, else the point 60 m into it. */
        fun leavesPlatform(route: WalkRoute): LatLon? {
            val level = setOf(WalkManeuver.Kind.STAIRS_UP, WalkManeuver.Kind.STAIRS_DOWN, WalkManeuver.Kind.STAIRS,
                WalkManeuver.Kind.ELEVATOR, WalkManeuver.Kind.LEVEL_UP, WalkManeuver.Kind.LEVEL_DOWN)
            route.maneuvers.firstOrNull { it.kind in level }?.takeIf { it.along <= PLATFORM_REACH }?.let { return it.coordinate }
            if (route.length < 30) return null
            return PathGeometry.point(minOf(60.0, route.length), route.path)
        }

        /** Which part of the train stops nearest `target`, along the direction of travel from the end of the ride's shape. */
        fun part(ride: Leg, target: LatLon): Part? {
            val stop = ride.to.coordinate
            val path = ride.legGeometry?.let { io.github.elija7580.publit.core.Polyline.decode(it.points, it.precision) }.orEmpty()
            val length = PathGeometry.length(path)
            if (path.size < 2 || length <= 20) return null
            val before = PathGeometry.point(maxOf(0.0, length - 120), path) ?: return null
            val end = path.last()
            if (before.distanceTo(end) <= 10) return null
            val heading = PathGeometry.bearing(before, end)
            val d = stop.distanceTo(target)
            if (d > MAX_REACH || d < MIDDLE_BAND) return null
            val ahead = d * cos(Math.toRadians(PathGeometry.turn(heading, PathGeometry.bearing(stop, target))))
            return when {
                ahead >= END_BAND -> Part.FRONT
                ahead <= -END_BAND -> Part.BACK
                abs(ahead) <= MIDDLE_BAND -> Part.MIDDLE
                else -> null
            }
        }
    }
}
