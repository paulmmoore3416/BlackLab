package com.blacklab.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {
    @Test fun lateMeansOverOneAndAHalfFrames() {
        assertFalse(FrameStats.isJanky(12_000_000, 120f))  // 12 ms at 120 Hz (budget 8.3 ms, limit 12.5 ms)
        assertTrue(FrameStats.isJanky(13_000_000, 120f))
        assertFalse(FrameStats.isJanky(24_000_000, 60f))   // limit 25 ms
        assertTrue(FrameStats.isJanky(26_000_000, 60f))
    }

    @Test fun countsPerScreenAndSortsWorstFirst() {
        FrameStats.reset()
        repeat(100) { FrameStats.record("home", 5_000_000, 120f) }
        repeat(90) { FrameStats.record("library", 5_000_000, 120f) }; repeat(10) { FrameStats.record("library", 40_000_000, 120f) }
        repeat(5) { FrameStats.record("tiny", 40_000_000, 120f) } // under 30 frames: not reported per screen
        val snap = FrameStats.snapshot()
        assertEquals(listOf("library", "home"), snap.map { it.route })
        assertEquals(90.0, snap[0].smoothPct, 0.001)
        assertEquals(205L, FrameStats.overall().frames)
        FrameStats.reset()
    }
}
