// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.core.Polyline
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.LegShape
import io.github.elija7580.publit.data.PlanResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/** The line of a ride must not run over tracks the ride never takes (iOS `LegShapeTests`, Publit#114). */
class LegShapeTest {
    // Paris, real answers of 27.11.2026, shortened (no walking steps, no fares): Porte de Pantin to Trocadéro
    // with métro 5 and 9, and Gare du Nord to Charles de Gaulle – Étoile with RER B and A. Same file as on iOS.
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    private fun answer(): PlanResponse =
        json.decodeFromString(PlanResponse.serializer(), javaClass.classLoader!!.getResource("plan_paris_shape_detours.json")!!.readText())

    private fun ride(res: PlanResponse, line: String): Leg = res.itineraries.flatMap { it.legs }.first { it.routeShortName == line }
    private fun points(leg: Leg): List<LatLon> = Polyline.decode(leg.legGeometry!!.points, leg.legGeometry!!.precision)
    private fun lineLength(leg: Leg): Double = LegShape.length(points(leg))
    private fun stopsLength(leg: Leg): Double = LegShape.length(leg.allStops.map { it.coordinate })

    @Test fun serverAnswerDrawsDetours() {
        val res = answer()
        // RER A, Châtelet – Auber – Étoile: 4.3 km from stop to stop, 17 km of line.
        val rerA: Leg = ride(res, "A")
        assertTrue(stopsLength(rerA) < 4_500)
        assertTrue(lineLength(rerA) > 16_000)
        // Métro 5, Porte de Pantin – République: 4.3 km from stop to stop, 8.3 km of line.
        val m5: Leg = ride(res, "5")
        assertTrue(stopsLength(m5) < 4_500)
        assertTrue(lineLength(m5) > 8_000)
    }

    @Test fun detoursBecomeTheStraightWayBetweenTheirStops() {
        val res = answer()
        for (line in listOf("A", "5")) {
            val before: Leg = ride(res, line)
            val after: Leg = LegShape.tidy(before)
            // About as long as the way from stop to stop.
            assertTrue(line, lineLength(after) < stopsLength(after) * 1.25)
            // It still starts and ends where the server's line did, and the ride itself is the same.
            val was: List<LatLon> = points(before)
            val now: List<LatLon> = points(after)
            assertEquals(was[0].lat, now[0].lat, 1e-5)
            assertEquals(was[0].lon, now[0].lon, 1e-5)
            assertEquals(was[was.size - 1].lat, now[now.size - 1].lat, 1e-5)
            assertEquals(was[was.size - 1].lon, now[now.size - 1].lon, 1e-5)
            assertEquals(before.allStops.map { it.name }, after.allStops.map { it.name })
            assertEquals(before.startTime, after.startTime)
            // The map draws the tidied line.
            assertTrue(line, LegShape.length(after.mapPath) < stopsLength(after) * 1.25)
        }
    }

    @Test fun rightLinesStayAsTheyAre() {
        val res = answer()
        // Métro 9 and RER B were drawn right; walks are never touched.
        for (line in listOf("9", "B")) {
            val before: Leg = ride(res, line)
            assertEquals(line, before.legGeometry!!.points, LegShape.tidy(before).legGeometry!!.points)
        }
        for (route in res.itineraries) {
            val tidied: io.github.elija7580.publit.data.Itinerary = LegShape.tidy(route)
            assertEquals(route.id, tidied.id)
            assertEquals(route.legs.size, tidied.legs.size)
            for (i in route.legs.indices) {
                if (!route.legs[i].isTransit) assertEquals(route.legs[i].legGeometry?.points, tidied.legs[i].legGeometry?.points)
            }
        }
    }

    /** Two stops 1 km apart on a west–east line; `detour` metres out to the north and back in between. */
    private fun made(detour: Double): Pair<List<LatLon>, List<LatLon>> {
        val a = LatLon(47.0, 9.0)
        val b = LatLon(47.0, 9.0 + 1_000 / (111_320 * cos(Math.toRadians(47.0))))
        val mid = LatLon(47.0, (a.lon + b.lon) / 2)
        val out = LatLon(47.0 + detour / 110_540, mid.lon)
        return listOf(a, mid, out, mid, b) to listOf(a, b)
    }

    @Test fun aShortWayRoundStays() {
        // 400 m out and back: 1.8 km for 1 km. A curve, not a mistake.
        val curve: Pair<List<LatLon>, List<LatLon>> = made(400.0)
        assertNull(LegShape.withoutDetours(curve.first, curve.second))
        // The Albula spirals: three times the straight way stays too.
        val spiral: Pair<List<LatLon>, List<LatLon>> = made(1_000.0)
        assertNull(LegShape.withoutDetours(spiral.first, spiral.second))
    }

    @Test fun aLongWayOutAndBackGoes() {
        val wrong: Pair<List<LatLon>, List<LatLon>> = made(2_000.0)
        assertEquals(wrong.second, LegShape.withoutDetours(wrong.first, wrong.second))
    }

    @Test fun stopsNextToEachOtherProveNothing() {
        // A loop line back to where it started: the two stops are the same place.
        val a: LatLon = LatLon(47.0, 9.0)
        assertNull(LegShape.withoutDetours(listOf(a, LatLon(47.02, 9.0), a), listOf(a, LatLon(47.0005, 9.0))))
    }

    @Test fun busesAndFerriesKeepTheirLine() {
        for (mode in listOf("BUS", "COACH", "FERRY", "WALK", "BIKE")) assertFalse(mode, mode in LegShape.railBound)
        for (mode in listOf("SUBWAY", "TRAM", "REGIONAL_RAIL", "SUBURBAN", "HIGHSPEED_RAIL")) assertTrue(mode, mode in LegShape.railBound)
        // The same wrong line on a bus stays as the server sent it.
        val rerA: Leg = ride(answer(), "A")
        val bus: Leg = rerA.copy(mode = "BUS")
        assertEquals(rerA.legGeometry!!.points, LegShape.tidy(bus).legGeometry!!.points)
    }
}
