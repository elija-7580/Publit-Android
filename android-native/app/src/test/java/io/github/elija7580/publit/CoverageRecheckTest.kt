package io.github.elija7580.publit

import io.github.elija7580.publit.core.LatLon
import io.github.elija7580.publit.data.CoverageArea
import io.github.elija7580.publit.data.CoverageAtlas
import io.github.elija7580.publit.data.CoverageGuide
import io.github.elija7580.publit.data.CoverageLevel
import io.github.elija7580.publit.data.CoverageNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS #76: many local lines on the map contradict a "next to no data" notice; recheck at most once an hour. */
class CoverageRecheckTest {
    private fun notice(level: CoverageLevel) = CoverageNotice(
        CoverageArea("x", level, emptyList(), "X", "X", "", LatLon(45.44, 10.99) to 7000.0, emptyList()), setOf(CoverageNotice.Role.HERE))

    @Test fun manyLocalLinesContradictASparseNotice() {
        assertTrue(CoverageGuide.isContradicted(notice(CoverageLevel.SPARSE), CoverageAtlas.CONTRADICTING_LINES))
        assertTrue(CoverageGuide.isContradicted(notice(CoverageLevel.NONE), 20))
        assertFalse(CoverageGuide.isContradicted(notice(CoverageLevel.SPARSE), CoverageAtlas.CONTRADICTING_LINES - 1))
        // "Missing: metro" stays true however many buses run.
        assertFalse(CoverageGuide.isContradicted(notice(CoverageLevel.PARTIAL), 50))
    }

    @Test fun recheckAtMostOnceAnHour() {
        val t = 1_800_000_000_000L
        assertTrue(CoverageAtlas.claimRecheck(t))
        assertFalse(CoverageAtlas.claimRecheck(t + 1_800_000))
        assertTrue(CoverageAtlas.claimRecheck(t + 3_700_000))
    }

    @Test fun downloadedAtlasMustLookWhole() {
        val area = "A|x|s||X|X||c48200,16370,5000"
        assertEquals("2026-11-15", CoverageAtlas.accept("D|2026-11-15\n" + List(20) { area }.joinToString("\n"))?.first)
        assertNull(CoverageAtlas.accept("D|2026-11-15\n" + List(3) { area }.joinToString("\n")))
        assertNull(CoverageAtlas.accept(""))
    }
}
