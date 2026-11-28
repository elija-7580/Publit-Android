// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.LiveMap
import io.github.elija7580.publit.data.ModeFilter
import io.github.elija7580.publit.data.RouteStyled
import io.github.elija7580.publit.data.StopTimesResponse
import io.github.elija7580.publit.data.TripSegment
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Feedback from a rider near Milan (Nov 2026, port of iOS Publit#212): Frecciarossa and Italo were missing on
 * the live map, Trenord trains showed only "RE6" without the train number riders look for on the board.
 */
class ItalianTrainsTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }

    /** `distance` is the whole trip's (927 km for a Frecciarossa Milan–Naples), not the hop's. */
    private fun segment(mode: String, name: String, tripId: String, polyline: String, distance: Double,
                        from: Triple<String, Double, Double>, to: Triple<String, Double, Double>) = """
        {"trips":[{"tripId":"$tripId","displayName":"$name"}],"mode":"$mode","distance":$distance,
         "from":{"name":"${from.first}","stopId":"${from.first}","lat":${from.second},"lon":${from.third}},
         "to":{"name":"${to.first}","stopId":"${to.first}","lat":${to.second},"lon":${to.third}},
         "departure":"2026-11-28T11:00:00Z","arrival":"2026-11-28T11:40:00Z",
         "scheduledDeparture":"2026-11-28T11:00:00Z","scheduledArrival":"2026-11-28T11:40:00Z",
         "realTime":false,"polyline":"$polyline"}
    """

    private fun segments(): List<TripSegment> {
        val list = listOf(
            segment("HIGHSPEED_RAIL", "FR 8809", "fr8809", "kvhtGwnkw@jrl@g`yAfd|AguzD", 927_000.0,
                Triple("MILANO ROGOREDO", 45.4335, 9.2390), Triple("REGGIO EMILIA AV", 44.7230, 10.6610)),
            segment("HIGHSPEED_RAIL", "FR 9617", "fr9617", "w_stG_tdw@nf{Tg}bS", 479_000.0,
                Triple("MILANO CENTRALE", 45.4862, 9.2040), Triple("ROMA TERMINI", 41.9010, 12.5010)),
            segment("REGIONAL_RAIL", "RE6", "re6", "gxrtGgbkw@oKgjIkCsyF", 139_000.0,
                Triple("Milano Lambrate", 45.4850, 9.2370), Triple("Pioltello Limito", 45.4877, 9.3301)),
            segment("BUS", "923", "bus923", "gxrtGgbkw@oKgjIkCsyF", 9_000.0,
                Triple("A", 45.4850, 9.2370), Triple("B", 45.4877, 9.3301)),
        )
        return json.decodeFromString(ListSerializer(TripSegment.serializer()), "[" + list.joinToString(",") + "]")
    }

    @Test fun highSpeedTrainsStayOnTheMap() {
        val ids = LiveMap.build(segments(), ModeFilter(), 5, 2.0).tracks.map { it.id }.toSet()
        assertTrue("a long trip with a real shape is drawn", "fr8809" in ids)
        assertFalse("a 400 km hop without a shape would be a straight line across Italy", "fr9617" in ids)
        assertTrue("re6" in ids)
        assertTrue("bus923" in ids)
    }

    @Test fun overviewKeepsOnlyTrains() {
        val ids = LiveMap.build(segments(), ModeFilter(), 5, 300.0, overview = true).tracks.map { it.id }.toSet()
        assertEquals(setOf("fr8809", "re6"), ids)
        assertTrue(LiveMap.tolerance(300_000.0) > 40)
        assertEquals(2.5, LiveMap.tolerance(2_000.0), 0.001)
    }

    private data class Train(override val mode: String, override val routeShortName: String?, override val displayName: String?,
                             override val tripShortName: String?, override val routeColor: String? = null,
                             override val routeTextColor: String? = null) : RouteStyled

    @Test fun trenordTrainNumberBesideTheLine() {
        val re6 = Train("REGIONAL_RAIL", "RE6", "RE6", "RE_6 - 2625")
        assertEquals("RE6", re6.lineName)
        assertEquals("2625", re6.tripNumber)
        assertEquals("RE 2625", re6.serviceDetail)
        assertEquals("24539", Train("REGIONAL_RAIL", "S5", "S5", "S5 - 24539").serviceDetail)
        val fr = Train("HIGHSPEED_RAIL", null, "FR 9304", "9304")
        assertNull(fr.tripNumber)
        assertNull(fr.serviceDetail)
        assertNull(Train("BUS", "923", "923", "923 - 4711").serviceDetail)
    }

    @Test fun stopTimeDecodesTrainNumberAndFirstStop() {
        val st = json.decodeFromString(StopTimesResponse.serializer(), """
            {"stopTimes":[{"place":{"name":"Pioltello Limito","lat":45.4877,"lon":9.3301,"departure":"2026-11-28T11:39:00Z"},
              "mode":"REGIONAL_RAIL","headsign":"Verona Porta Nuova","tripId":"t1","routeShortName":"RE6","displayName":"RE6",
              "tripShortName":"RE_6 - 2625","tripFrom":{"name":"Milano Centrale","lat":45.487,"lon":9.203},
              "tripTo":{"name":"Verona Porta Nuova","lat":45.429,"lon":10.982}}]}
        """).stopTimes.first()
        assertEquals("Milano Centrale", st.tripFrom?.name)
        assertEquals("RE 2625", st.serviceDetail)
    }
}
