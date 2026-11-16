package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.ChangeWatch
import io.github.elija7580.publit.data.DelayHistory
import io.github.elija7580.publit.data.Itinerary
import io.github.elija7580.publit.data.Leg
import io.github.elija7580.publit.data.Place
import io.github.elija7580.publit.data.SmartRouting
import io.github.elija7580.publit.data.TransferWalk
import io.github.elija7580.publit.data.TripLearning
import io.github.elija7580.publit.data.WalkModel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/** Same cases as iOS ChangeChanceTests and TripLearningTests (measured change chances, learning, alerts). */
class ChangeChanceTest {
    private val t0 = Instant.ofEpochSecond(1_791_360_000)
    private fun at(min: Double): Instant = t0.plusMillis((min * 60_000).toLong())

    private fun leg(mode: String, from: String, to: String, start: Int, end: Int, delay: Long = 0, realTime: Boolean = false,
                    route: String? = "de-T_R1", trip: String? = null, toTrack: String? = null, toSched: String? = null,
                    cancelled: Boolean = false, fromLat: Double = 48.0, toLat: Double = 48.0) = Leg(
        mode = mode, from = Place(name = from, stopId = "de-T_$from", lat = fromLat, lon = 11.0),
        to = Place(name = to, stopId = "de-T_$to", lat = toLat, lon = 11.0, track = toTrack, scheduledTrack = toSched ?: toTrack),
        duration = (end - start) * 60, startTime = at(start.toDouble()).plusSeconds(delay), endTime = at(end.toDouble()).plusSeconds(delay),
        scheduledStartTime = at(start.toDouble()), scheduledEndTime = at(end.toDouble()), realTime = realTime, routeId = route,
        tripId = trip ?: "$mode-$from-$start", cancelled = cancelled)

    private val table = DelayHistory.Table("""
        D|2026-11-17|30|2026-10-19
        R|de-T_R1|w|-60,0,90,240,480|-60,0,60,180,420|10|500
        S|de-T_R1|de-T_B|w|0,60,180,360,600|0,60,180,360,600|10|120
        R|de-T_R2|w|-30,0,0,30,60|-30,0,0,30,60|0|300
        R|de-T_R3|w|0,60,180,300,600|0,60,180,300,600|0|200
        O|de-T|Bahn A|w|0,0,30,60,120|0,0,30,60,120|0|900
        M|BUS|w|-30,0,60,120,240|-30,0,30,90,180|20|5000
        H|REGIONAL_RAIL|5|-30,-10,0,10,30|800
        H|REGIONAL_RAIL|60|-120,-60,0,120,300|800
    """.trimIndent())
    private val history: (String?) -> DelayHistory.Table? = { if (it == "de") table else null }

    private fun change(a: Leg, b: Leg, now: Instant = t0.minusSeconds(7200), walk: TransferWalk = TransferWalk(120.0)) =
        SmartRouting.chance(a, b, walk, now, history)

    @Test fun sharedVectorsMatchThePythonReference() {
        val text = javaClass.classLoader!!.getResource("change-chance-vectors.json")!!.readText()
        val cases = Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray
        assertTrue(cases.isNotEmpty())
        for (e in cases) {
            val c = e.jsonObject
            fun d(k: String) = c[k]!!.jsonPrimitive.double
            fun q(k: String) = DelayHistory.Dist(c[k]!!.jsonArray.map { it.jsonPrimitive.double })
            val p = SmartRouting.onTime(d("gap"), q("arr"), q("dep"), WalkModel.PRIOR.samples(TransferWalk(d("walk"))))
            var v = (1 - d("cin")) * (1 - d("cout")) * p
            c["n"]?.jsonPrimitive?.intOrNull?.let { v = SmartRouting.cap(v, it) }
            assertEquals(c["name"]!!.jsonPrimitive.content, d("expected"), v, 0.0001)
        }
    }

    @Test fun tableFallsBackFromStopToRouteToOperatorToMode() {
        assertEquals(120, table.row("de-T_R1", "de-T_B", null, "BUS", "w")?.observations)
        assertEquals(500, table.row("de-T_R1", "de-T_X", null, "BUS", "w")?.observations)
        assertEquals(900, table.row("de-T_R9", null, "Bahn A", "BUS", "w")?.observations)
        assertEquals(5000, table.row("de-T_R9", null, "Other", "BUS", "w")?.observations)
        assertNull(table.row("de-T_R9", null, null, "TRAM", "w"))
        assertEquals("de", DelayHistory.country("de-DELFI_de:1"))
        val utc = ZoneId.of("UTC")
        val mon = Instant.ofEpochSecond(1_794_787_200)
        assertEquals("p", DelayHistory.window(mon.plusSeconds(7 * 3600), utc))
        assertEquals("e", DelayHistory.window(mon.plusSeconds(5 * 86400 + 12 * 3600), utc))
        assertEquals("n", DelayHistory.window(mon.plusSeconds(23 * 3600), utc))
    }

    @Test fun usesHistoryOfBothVehiclesAndLiveForecast() {
        val feeder = leg("REGIONAL_RAIL", "A", "B", 0, 30)
        val p = change(feeder, leg("BUS", "B", "C", 35, 50, route = "de-T_R2"))
        val l = change(feeder, leg("BUS", "B", "C", 35, 50, route = "de-T_R3"))
        assertEquals(SmartRouting.Change.Source.History(120), p.second)
        assertTrue(p.first < l.first)
        val live = leg("REGIONAL_RAIL", "A", "B", 0, 30, delay = 60, realTime = true)
        val next = leg("BUS", "B", "C", 34, 50, route = "de-T_R2")
        val arrival = at(31.0)
        assertTrue(change(live, next, arrival.minusSeconds(300)).first > change(live, next, arrival.minusSeconds(3600)).first)
    }

    @Test fun platformChangeThroughRunCancellationAndFallback() {
        val moved = leg("REGIONAL_RAIL", "A", "B", 0, 30, toTrack = "5", toSched = "2")
        val next = leg("BUS", "B", "C", 34, 50, route = "de-T_R2")
        val w = TransferWalk.of(moved, emptyList(), next)
        assertTrue(w.platformChanged)
        val same = leg("REGIONAL_RAIL", "A", "B", 0, 30, toTrack = "2")
        assertTrue(change(moved, next, walk = w).first < change(same, next, walk = TransferWalk.of(same, emptyList(), next)).first)
        val a = leg("REGIONAL_RAIL", "A", "B", 0, 30, trip = "T1"); val b = leg("REGIONAL_RAIL", "B", "C", 31, 50, trip = "T1")
        assertEquals(1.0 to SmartRouting.Change.Source.ThroughRun, change(a, b))
        assertTrue(change(a, leg("BUS", "B", "C", 40, 50, route = "de-T_R2", cancelled = true)).first < 0.05)
        val x = leg("TRAM", "A", "B", 0, 10, route = "at-X_1"); val y = leg("TRAM", "B", "C", 14, 20, route = "at-X_2")
        val est = change(x, y)
        assertEquals(SmartRouting.Change.Source.Estimate, est.second)
        assertEquals(SmartRouting.chance(2.0, 120, x), est.first, 0.0001)
    }

    // Learning

    private fun obs(planned: Double, actual: Double?, levels: Double = 0.0, lifts: Int = 0, made: Boolean = true, key: String = "k") =
        TripLearning.Observation(0, key, TransferWalk(planned, levels, lifts), actual, made)

    @Test fun walkModelStartsFromPriorAndLearns() {
        assertEquals(WalkModel.PRIOR, TripLearning.walkModel(emptyList()))
        val walk = TransferWalk(180.0)
        val few = TripLearning.walkModel(List(2) { obs(180.0, 360.0) })
        val many = TripLearning.walkModel(List(60) { i -> val p = 120.0 + i % 5 * 60; obs(p, p * 2) })
        assertTrue(few.expectedSeconds(walk) > WalkModel.PRIOR.expectedSeconds(walk))
        assertTrue(few.expectedSeconds(walk) < 280)
        assertEquals(360.0, many.expectedSeconds(walk), 25.0)
        assertTrue(many.spread(walk) < WalkModel.PRIOR.spread(walk))
    }

    @Test fun stairsAndLiftsCarryOver() {
        val list = List(40) { i -> val lv = (i % 4).toDouble(); val lf = if (i % 3 == 0) 1 else 0; obs(150.0, 150 * 1.05 + lv * 40 + lf * 90, lv, lf) }
        val m = TripLearning.walkModel(list)
        assertTrue(m.perLevel > 25); assertTrue(m.perElevator > 60)
        val unknown = TransferWalk(200.0, 5.0, 2)
        assertTrue(m.expectedSeconds(unknown) > WalkModel.PRIOR.expectedSeconds(unknown) + 120)
    }

    @Test fun ownSuccessRate() {
        val missed = List(5) { obs(120.0, 150.0, made = false, key = "A") }
        assertTrue(TripLearning.adjusted(0.9, "A", missed) < 0.5)
        assertEquals(0.9, TripLearning.adjusted(0.9, "B", missed), 0.0)
        assertEquals(0.9, TripLearning.adjusted(0.9, "A", missed.take(2)), 0.0)
    }

    private fun trip(cancelled: Boolean = false): Itinerary {
        val legs = listOf(leg("BUS", "A", "B", 0, 10, trip = "t1", route = null, fromLat = 48.0, toLat = 48.05),
            leg("WALK", "B", "B2", 10, 12, trip = "w", route = null, fromLat = 48.05, toLat = 48.0505),
            leg("BUS", "B2", "C", 15, 30, trip = "t2", route = null, cancelled = cancelled, fromLat = 48.0505, toLat = 48.15))
        return Itinerary(id = "i", duration = 1800, startTime = legs[0].startTime, endTime = legs[2].endTime, transfers = 1, legs = legs)
    }

    @Test fun recorderMeasuresWalkAndOutcome() {
        val it = trip()
        val rec = TripLearning.Recorder()
        assertTrue(rec.observe(LatLon(48.0505, 11.0), 10.0, at(13.5), it).isEmpty())
        val found = rec.observe(LatLon(48.08, 11.0), 10.0, at(20.0), it)
        assertEquals(1, found.size)
        assertEquals(210.0, found[0].walkSeconds!!, 1.0)
        assertTrue(found[0].made)
        val missed = TripLearning.Recorder()
        assertTrue(missed.observe(LatLon(48.0505, 11.0), 200.0, at(13.0), it).isEmpty())
        assertFalse(missed.observe(LatLon(48.0505, 11.0), 10.0, at(21.0), it).single().made)
    }

    // Alerts

    private fun ch(chance: Double, src: SmartRouting.Change.Source = SmartRouting.Change.Source.History(100), cancelled: Boolean = false): SmartRouting.Change {
        val it = trip(cancelled)
        return SmartRouting.Change("B", it.legs[0], it.legs[2], 2, 120, 3.0, chance, src)
    }

    @Test fun alertLogic() {
        val now = at(5.0)
        assertTrue(ChangeWatch.due(listOf(ch(0.8)), emptyMap(), now).isEmpty())
        assertEquals(1, ChangeWatch.due(listOf(ch(0.6)), emptyMap(), now).size)
        assertEquals(1, ChangeWatch.due(listOf(ch(0.01, cancelled = true)), emptyMap(), now).size)
        assertTrue(ChangeWatch.due(listOf(ch(0.2, SmartRouting.Change.Source.ThroughRun)), emptyMap(), now).isEmpty())
        val warned = mapOf(ChangeWatch.key(ch(0.6)) to 0.6)
        assertTrue(ChangeWatch.due(listOf(ch(0.55)), warned, now).isEmpty())
        assertEquals(1, ChangeWatch.due(listOf(ch(0.4)), warned, now).size)
        assertTrue(ChangeWatch.due(listOf(ch(0.01, cancelled = true)), mapOf(ChangeWatch.key(ch(0.6)) to 0.0), now).isEmpty())
        val far = ChangeWatch.onFoot(ch(0.9), LatLon(48.046, 11.0), at(14.0), 1.3, null, WalkModel.PRIOR)!!
        val there = ChangeWatch.onFoot(ch(0.9), LatLon(48.0505, 11.0), at(14.0), 1.3, null, WalkModel.PRIOR)!!
        assertTrue(far < 0.1); assertTrue(there > 0.9)
    }
}
