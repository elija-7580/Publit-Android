// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich

package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.EncodedPolyline
import io.github.elija7580.publit.data.ExitAdvice
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.StationEntrance
import io.github.elija7580.publit.data.StationExits
import io.github.elija7580.publit.data.StepInstruction
import io.github.elija7580.publit.data.WalkRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.math.cos
import kotlin.math.roundToLong

/** Where to ride in the train and which way out, per trip (ExitAdviceTests on iOS). */
class ExitAdviceTest {
    private val t = Instant.parse("2026-11-23T08:00:00Z")
    private val station = LatLon(48.2000, 16.3700)

    private fun north(p: LatLon, m: Double) = LatLon(p.lat + m / 110_540.0, p.lon)
    private fun east(p: LatLon, m: Double) = LatLon(p.lat, p.lon + m / (111_320.0 * cos(Math.toRadians(p.lat))))
    private fun place(name: String, p: LatLon) = Place(name = name, lat = p.lat, lon = p.lon)

    private fun encode(points: List<LatLon>): EncodedPolyline {
        val sb = StringBuilder(); var lat = 0L; var lon = 0L
        fun put(v: Long) { var x = if (v < 0) (v shl 1).inv() else v shl 1; while (x >= 0x20) { sb.append(((0x20 or (x and 0x1f).toInt()) + 63).toChar()); x = x shr 5 }; sb.append((x + 63).toInt().toChar()) }
        for (p in points) { val a = (p.lat * 1e6).roundToLong(); val b = (p.lon * 1e6).roundToLong(); put(a - lat); put(b - lon); lat = a; lon = b }
        return EncodedPolyline(sb.toString(), 6, points.size)
    }

    /** A metro ride into the station, travelling north (or south). */
    private fun ride(northbound: Boolean = true, mode: String = "SUBWAY", end: LatLon = station) =
        Leg(mode = mode, from = place("A", north(end, if (northbound) -1500.0 else 1500.0)), to = place("Station", end), duration = 300,
            startTime = t, endTime = t.plusSeconds(300), legGeometry = encode(listOf(north(end, if (northbound) -1500.0 else 1500.0), north(end, if (northbound) -300.0 else 300.0), end)))

    private val stairs = north(station, 60.0)
    private val street = east(stairs, 10.0)
    private val home = east(street, 300.0)

    /** Off the train, 60 m north along the platform, up the stairs, then east to the destination. */
    private fun walkOut() = Leg(mode = "WALK", from = place("Station", station), to = place("Home", home), duration = 300,
        startTime = t.plusSeconds(300), endTime = t.plusSeconds(600), distance = 370.0, steps = listOf(
            StepInstruction("DEPART", 60.0, fromLevel = -1.0, toLevel = -1.0, polyline = encode(listOf(station, stairs))),
            StepInstruction("STAIRS", 10.0, fromLevel = -1.0, toLevel = 0.0, polyline = encode(listOf(stairs, street))),
            StepInstruction("RIGHT", 300.0, fromLevel = 0.0, toLevel = 0.0, polyline = encode(listOf(street, home)), streetName = "Hauptstraße"),
        ))

    @Before fun reset() { StationExits.installPreferred(emptyMap(), null) }

    @Test fun theWayOutDecidesThePartOfTheTrain() {
        val legs = listOf(ride(), walkOut())
        val a = ExitAdvice.forRide(0, legs)!!
        assertEquals(ExitAdvice.Part.FRONT, a.part)
        assertFalse(a.forChange)
        assertEquals(ExitAdvice.Part.BACK, ExitAdvice.forRide(0, listOf(ride(northbound = false), walkOut()))?.part)
        assertNull("walks get none", ExitAdvice.forRide(1, legs))
        assertNull("trams stop in the street", ExitAdvice.forRide(0, listOf(ride(mode = "TRAM"), walkOut())))
    }

    @Test fun theExitNumberAndTheUsersOwnExit() {
        val walk = walkOut()
        val legs = listOf(ride(), walk)
        val a = StationEntrance(1, "A", east(stairs, 12.0))
        val b = StationEntrance(2, "B", north(station, -200.0))
        StationExits.store(listOf(b, a, StationEntrance(3, "A", east(stairs, 14.0))), walk)
        val auto = ExitAdvice.forRide(0, legs)!!
        assertEquals("A", auto.door)
        assertFalse(auto.doorSaved)
        assertEquals("Front of the train · Exit A", auto.short)
        assertEquals(listOf("A", "B"), StationExits.choices(walk).map { it.ref })

        val saved = HashMap<String, String>()
        StationExits.installPreferred(emptyMap()) { saved.clear(); saved.putAll(it) }
        StationExits.setPreferred(b, walk)
        val own = ExitAdvice.forRide(0, legs)!!
        assertEquals("B", own.door)
        assertTrue(own.doorSaved)
        assertEquals("B", WalkRoute(walk).maneuvers.firstOrNull { it.exitRef != null }?.exitRef)
        assertEquals("2|B", saved[StationExits.preferenceKey(walk)])
        // Another destination from the same station is another choice.
        assertNotEquals(StationExits.preferenceKey(walk), StationExits.preferenceKey(walk.copy(to = place("Work", north(station, 3000.0)))))
        StationExits.setPreferred(null, walk)
        assertEquals("A", ExitAdvice.forRide(0, legs)?.door)
    }

    @Test fun aChangeAimsAtTheNextTrain() {
        val platform2 = north(station, -80.0)
        val change = Leg(mode = "WALK", from = place("Station", station), to = place("Station", platform2), duration = 120,
            startTime = t.plusSeconds(300), endTime = t.plusSeconds(420), distance = 80.0, steps = listOf(
                StepInstruction("DEPART", 80.0, fromLevel = -1.0, toLevel = -1.0, polyline = encode(listOf(station, platform2)))))
        val next = Leg(mode = "SUBWAY", from = place("Station", platform2), to = place("B", east(platform2, 2000.0)), duration = 300,
            startTime = t.plusSeconds(480), endTime = t.plusSeconds(780))
        val a = ExitAdvice.forRide(0, listOf(ride(), change, next))!!
        assertTrue(a.forChange)
        assertEquals(ExitAdvice.Part.BACK, a.part)
        assertEquals("Closest to your next train", a.reason)
        assertNull(a.door)
    }

    @Test fun nothingRightWhereTheTrainStopsOrFarAway() {
        assertNull(ExitAdvice.part(ride(), north(station, 5.0)))
        assertNull(ExitAdvice.part(ride(), north(station, 900.0)))
        assertEquals(ExitAdvice.Part.MIDDLE, ExitAdvice.part(ride(), east(station, 40.0)))
    }
}
