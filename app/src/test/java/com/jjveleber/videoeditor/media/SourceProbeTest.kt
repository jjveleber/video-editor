package com.jjveleber.videoeditor.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SourceProbeTest {
    @Test
    fun displaySizeSwapsForQuarterTurns() {
        assertEquals(1080 to 1920, displaySize(1920, 1080, 90))
        assertEquals(1080 to 1920, displaySize(1920, 1080, 270))
        assertEquals(1080 to 1920, displaySize(1920, 1080, -90))
    }

    @Test
    fun displaySizeKeepsForHalfTurns() {
        assertEquals(1920 to 1080, displaySize(1920, 1080, 0))
        assertEquals(1920 to 1080, displaySize(1920, 1080, 180))
    }

    @Test
    fun toSegmentCoversFullSource() {
        val s = ProbeResult(3_000_000L, 640, 360, false).toSegment("content://x", id = "s")
        assertEquals("s", s.id)
        assertEquals("content://x", s.sourceUri)
        assertEquals(0L, s.startUs)
        assertEquals(3_000_000L, s.endUs)
        assertEquals(3_000_000L, s.sourceDurationUs)
        assertEquals(false, s.hasAudio)
        assertEquals(640 to 360, s.width to s.height)
    }
}
