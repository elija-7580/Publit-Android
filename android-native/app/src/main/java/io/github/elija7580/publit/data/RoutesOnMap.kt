// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit.data

import io.github.elija7580.publit.core.LatLon

/**
 * All routes of a search on one map (iOS `CompareRoutesMapView` and `CompareMapView`, build 99): which route
 * is drawn strong, in which order the routes are drawn, and what the map has to show.
 */
object RoutesOnMap {
    /** The route drawn strong: the picked one, or the first when nothing is picked or the pick is no longer listed. */
    fun current(routes: List<Itinerary>, picked: String?): Itinerary? =
        routes.firstOrNull { it.routeKey == picked } ?: routes.firstOrNull()

    /** Drawing order: the strong route last, so it lies on top of the others. */
    fun ordered(routes: List<Itinerary>, strong: String?): List<Itinerary> =
        routes.filter { it.routeKey != strong } + routes.filter { it.routeKey == strong }

    /**
     * Every point the map frames: the shapes of all routes with their starts and ends. The same for every
     * pick, so the map stays where it is when another route is chosen.
     */
    fun frame(routes: List<Itinerary>): List<LatLon> =
        routes.flatMap { r -> r.legs.flatMap { l -> l.mapPath + listOf(l.from.coordinate, l.to.coordinate) } }
}
