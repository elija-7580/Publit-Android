// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Elija Samuel Friedrich-Ulrich
package io.github.elija7580.publit

import io.github.elija7580.publit.data.LiveMap
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vehicles on the map glide instead of hopping, and a bus standing at a stop stays (Munich, Nov. 2026). */
class LiveMapPaceTest {
    @Test fun vehiclesMoveOftenEnoughToGlide() {
        assertTrue(LiveMap.frameMillis(batterySaver = false) <= 500)
        assertTrue(LiveMap.frameMillis(batterySaver = true) <= 2_000)
        assertTrue(LiveMap.frameMillis(batterySaver = true) > LiveMap.frameMillis(batterySaver = false))
    }

    @Test fun busStandingAtAStopIsStillAskedFor() {
        assertTrue(LiveMap.LOOK_BACK_SECONDS >= 120)
    }
}
