// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.PlanResponse
import io.github.elija7580.publit.data.StopTimesResponse
import io.github.elija7580.publit.data.TransitousClient
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One odd route or departure does not fail the whole answer (iOS `LossyList`, `StabilityAuditTests`; Publit#208).
 */
class TolerantAnswersTest {
    private val json: Json = TransitousClient().json

    private fun route(id: String, start: String = "2026-10-29T18:00:00Z", end: String = "2026-10-29T18:10:00Z") =
        """{"id":"$id","duration":600,"startTime":"$start","endTime":"$end","transfers":0,"legs":[{"mode":"SUBWAY",
            "from":{"name":"A","lat":48.2,"lon":16.37},"to":{"name":"B","lat":48.21,"lon":16.38},
            "startTime":"$start","endTime":"$end","tripId":"T-$id"}]}"""

    private fun departure(trip: String) =
        """{"place":{"name":"Karlsplatz","stopId":"x","lat":48.2,"lon":16.37,"departure":"2026-10-29T18:05:00Z"},
            "mode":"SUBWAY","headsign":"Leopoldau","tripId":"$trip","routeShortName":"U1"}"""

    @Test fun anAnswerThatReadsFineIsUnchanged() {
        val res = json.decodeFromString(PlanResponse.serializer(),
            """{"itineraries":[${route("a")},${route("b")}],"direct":[${route("w")}],"previousPageCursor":"p","nextPageCursor":"n"}""")
        assertEquals(listOf("a", "b"), res.itineraries.map { it.id })
        assertEquals(listOf("w"), res.direct.map { it.id })
        assertEquals("p", res.previousPageCursor)
        assertEquals("n", res.nextPageCursor)
        assertTrue(json.decodeFromString(PlanResponse.serializer(), "{}").itineraries.isEmpty())
    }

    @Test fun oneUnreadableRouteIsSkippedAndTheOthersStay() {
        val noEnd = route("b").replace("\"endTime\"", "\"endTimeX\"")
        val noLegs = """{"id":"e","duration":600,"startTime":"2026-10-29T18:00:00Z","endTime":"2026-10-29T18:10:00Z"}"""
        val body = """{"itineraries":[${route("a")},$noEnd,"odd",${route("c", start = "soon")},$noLegs,${route("d")}],
            "direct":[{"duration":1},${route("w")}],"nextPageCursor":"n"}"""
        val res = json.decodeFromString(PlanResponse.serializer(), body)
        assertEquals(listOf("a", "d"), res.itineraries.map { it.id })
        assertEquals(listOf("w"), res.direct.map { it.id })
        assertEquals("n", res.nextPageCursor)
    }

    @Test fun oneUnreadableDepartureIsSkippedAndTheOthersStay() {
        val noTrip = departure("t2").replace("\"tripId\"", "\"tripIdX\"")
        val noPlace = """{"mode":"BUS","tripId":"t3"}"""
        val res = json.decodeFromString(StopTimesResponse.serializer(),
            """{"stopTimes":[${departure("t1")},$noTrip,$noPlace,${departure("t4")}],"nextPageCursor":"c"}""")
        assertEquals(listOf("t1", "t4"), res.stopTimes.map { it.tripId })
        assertEquals("c", res.nextPageCursor)
    }

    /** Not what the API promises at all: still an error, not an empty list. */
    @Test fun aListThatIsNoListStillFails() {
        assertTrue(runCatching { json.decodeFromString(PlanResponse.serializer(), """{"itineraries":{"a":1}}""") }.isFailure)
        assertTrue(runCatching { json.decodeFromString(StopTimesResponse.serializer(), """{"stopTimes":"none"}""") }.isFailure)
    }
}
