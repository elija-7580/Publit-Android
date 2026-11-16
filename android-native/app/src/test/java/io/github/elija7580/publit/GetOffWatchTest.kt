package io.github.elija7580.publit

import io.github.elija7580.publit.data.GetOffAlert
import io.github.elija7580.publit.data.GetOffWatch
import io.github.elija7580.publit.data.TripProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same cases as iOS GetOffWatchTests (#69): a ride with 6 stops, 0 … 5, getting off at 5. */
class GetOffWatchTest {
    private val n = 6

    @Test fun oneStopBeforeFiresAtTheStopBeforeNotEarlier() {
        assertFalse(GetOffWatch.isDue(GetOffAlert.ONE_STOP_BEFORE, TripProgress.Between(3, 0.9), n, 900.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.ONE_STOP_BEFORE, TripProgress.AtStop(4), n, 600.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.ONE_STOP_BEFORE, TripProgress.Between(4, 0.2), n, 500.0))
        assertFalse(GetOffWatch.isDue(GetOffAlert.ONE_STOP_BEFORE, TripProgress.Finished, n, 0.0))
    }

    @Test fun twoStopsBefore() {
        assertFalse(GetOffWatch.isDue(GetOffAlert.TWO_STOPS_BEFORE, TripProgress.Between(2, 0.5), n, 2000.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.TWO_STOPS_BEFORE, TripProgress.AtStop(3), n, 1500.0))
    }

    @Test fun approachingUsesDistanceOnlyOnTheLastStretch() {
        assertFalse(GetOffWatch.isDue(GetOffAlert.APPROACHING, TripProgress.Between(2, 0.5), n, 300.0))
        assertFalse(GetOffWatch.isDue(GetOffAlert.APPROACHING, TripProgress.Between(4, 0.1), n, 900.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.APPROACHING, TripProgress.Between(4, 0.7), n, 350.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.APPROACHING, TripProgress.AtStop(5), n, 0.0))
    }

    @Test fun clockOnlyNeverUsesLocation() =
        assertFalse(GetOffWatch.isDue(GetOffAlert.TIME_ONLY, TripProgress.AtStop(4), n, 100.0))

    @Test fun shortRideMilestoneIsTheFirstStopLeft() {
        assertFalse(GetOffWatch.isDue(GetOffAlert.TWO_STOPS_BEFORE, TripProgress.AtStop(0), 2, 800.0))
        assertTrue(GetOffWatch.isDue(GetOffAlert.TWO_STOPS_BEFORE, TripProgress.Between(0, 0.3), 2, 600.0))
    }

    @Test fun rawValuesAsOnIos() {
        assertEquals(GetOffAlert.ONE_STOP_BEFORE, GetOffAlert.of(null))
        assertEquals(GetOffAlert.APPROACHING, GetOffAlert.of("approaching"))
        assertEquals(listOf("oneStopBefore", "twoStopsBefore", "approaching", "timeOnly"), GetOffAlert.entries.map { it.raw })
    }
}
