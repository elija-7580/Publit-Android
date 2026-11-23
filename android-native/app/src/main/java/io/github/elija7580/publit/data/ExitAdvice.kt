// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.L10n
import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import kotlin.math.abs
import kotlin.math.cos

/**
 * Where to ride in the train and which way out, for a ride that ends in a station: "Board at the front ·
 * Closest to exit B2" (ExitAdvice on iOS). Worked out per trip from where the walk after the ride leaves the
 * platform (stairs, lift, the station door it uses) or, at a change, where the next train leaves. The door can
 * also be one the user picked, which Publit remembers for that station and destination.
 */
data class ExitAdvice(
    val part: Part? = null,
    /** The station door to take (its sign: "B2"), when the walk after the ride leaves the station. */
    val door: String? = null,
    /** The user chose this door (remembered for the station and destination). */
    val doorSaved: Boolean = false,
    /** The part aims at the next train (a change in the station) rather than the way out. */
    val forChange: Boolean = false,
) {
    enum class Part { FRONT, MIDDLE, BACK }

    val isEmpty get() = part == null && door == null

    /** "Board at the front": before the ride. */
    val boardText: String? get() = when (part) {
        Part.FRONT -> L10n.t("Board at the front"); Part.MIDDLE -> L10n.t("Board in the middle"); Part.BACK -> L10n.t("Board at the back"); null -> null
    }

    /** "Front of the train", for a line that already names the ride. */
    val partText: String? get() = when (part) {
        Part.FRONT -> L10n.t("Front of the train"); Part.MIDDLE -> L10n.t("Middle of the train"); Part.BACK -> L10n.t("Back of the train"); null -> null
    }

    /** Why that part: "Closest to exit B2", "Closest to your next train", "Closest to the way out". */
    val reason: String? get() = when {
        part == null -> null
        forChange -> L10n.t("Closest to your next train")
        door != null -> L10n.f("Closest to exit %@", door)
        else -> L10n.t("Closest to the way out")
    }

    /** One short line for on board and the live notification: "Front of the train · Exit B2". */
    val short: String? get() = listOfNotNull(partText, door?.let { L10n.f("Exit %@", it) }).joinToString(" · ").ifEmpty { null }

    companion object {
        /** Rides that stop at a platform in a station: metro and trains (STATION_MODES on iOS). */
        val STATION_MODES = setOf("SUBWAY", "METRO", "HIGHSPEED_RAIL", "LONG_DISTANCE", "REGIONAL_RAIL",
            "REGIONAL_FAST_RAIL", "NIGHT_RAIL", "RAIL", "SUBURBAN")
        const val END_BAND = 25.0
        const val MIDDLE_BAND = 10.0
        const val MAX_REACH = 400.0
        const val PLATFORM_REACH = 250.0

        fun applies(ride: Leg) = ride.isTransit && ride.mode in STATION_MODES

        /** The advice for the ride at [index], or null when there is nothing to say. */
        fun forRide(index: Int, legs: List<Leg>): ExitAdvice? {
            val ride = legs.getOrNull(index)?.takeIf(::applies) ?: return null
            var door: String? = null; var saved = false; var forChange = false
            var target: LatLon? = null
            val walk = legs.getOrNull(index + 1)
            if (walk != null && !walk.isTransit) {
                val route = WalkRoute(walk)
                val doorAt = route.maneuvers.firstOrNull { it.exitRef != null }
                if (StationExits.leavesUnderground(legs, index + 1)) {
                    door = doorAt?.exitRef
                    saved = door != null && StationExits.preferred(walk)?.ref == door
                } else forChange = legs.getOrNull(index + 2)?.isTransit == true
                target = leavesPlatform(route) ?: doorAt?.exitPoint
                if (target == null && forChange) target = legs[index + 2].from.coordinate
            } else if (walk != null && walk.isTransit) {
                forChange = true; target = walk.from.coordinate
            }
            val advice = ExitAdvice(target?.let { part(ride, it) }, door, saved, forChange)
            return advice.takeUnless { it.isEmpty }
        }

        /** Advice for every ride of the trip, by leg index. */
        fun all(legs: List<Leg>): Map<Int, ExitAdvice> = legs.indices.mapNotNull { i -> forRide(i, legs)?.let { i to it } }.toMap()

        /** Where the walk leaves the platform: its first stairs, escalator or lift; failing that, the way it heads (60 m in). */
        fun leavesPlatform(route: WalkRoute): LatLon? {
            val level = setOf(WalkManeuver.Kind.STAIRS_UP, WalkManeuver.Kind.STAIRS_DOWN, WalkManeuver.Kind.STAIRS,
                WalkManeuver.Kind.ELEVATOR, WalkManeuver.Kind.LEVEL_UP, WalkManeuver.Kind.LEVEL_DOWN)
            route.maneuvers.firstOrNull { it.kind in level }?.takeIf { it.along <= PLATFORM_REACH }?.let { return it.coordinate }
            if (route.length < 30) return null
            return PathGeometry.point(minOf(60.0, route.length), route.path) ?: route.path.lastOrNull()
        }

        /** Which part of the train stops nearest [target], measured along the direction of travel at the end of the ride. */
        fun part(ride: Leg, target: LatLon): Part? {
            val stop = ride.to.coordinate
            val path = ride.legGeometry?.let { Polyline.decode(it.points, it.precision) }.orEmpty()
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
