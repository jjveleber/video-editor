package com.jjveleber.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimingTest {
    private fun seg(id: String, durationUs: Long) =
        Segment(id, "file:///$id.mp4", 10_000_000L, 0L, durationUs, true, 1920, 1080)

    private val project = Project("p", "P", listOf(seg("a", 1_000_000L), seg("b", 2_000_000L)), 0L, 0L)

    @Test
    fun segmentDurationUsesTrimmedRange() {
        val s = seg("x", 0L).copy(startUs = 1_000_000L, endUs = 3_000_000L)
        assertEquals(2_000_000L, s.durationUs)
    }

    @Test
    fun totalDurationSumsSegments() {
        assertEquals(3_000_000L, project.totalDurationUs)
    }

    @Test
    fun startOfSegment() {
        assertEquals(0L, project.startOfSegmentUs(0))
        assertEquals(1_000_000L, project.startOfSegmentUs(1))
    }

    @Test
    fun locateEmptyIsNull() {
        assertNull(project.copy(segments = emptyList()).locate(0L))
    }

    @Test
    fun locateNegativeClampsToStart() {
        assertEquals(SegmentPosition(0, 0L), project.locate(-5L))
    }

    @Test
    fun locateInsideSecond() {
        assertEquals(SegmentPosition(1, 500_000L), project.locate(1_500_000L))
    }

    @Test
    fun locateBoundaryBelongsToNext() {
        assertEquals(SegmentPosition(1, 0L), project.locate(1_000_000L))
    }

    @Test
    fun locateEndAndBeyondClampToLastEnd() {
        assertEquals(SegmentPosition(1, 2_000_000L), project.locate(3_000_000L))
        assertEquals(SegmentPosition(1, 2_000_000L), project.locate(9_000_000L))
    }
}
