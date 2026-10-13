package com.jjveleber.videoeditor.media

import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositionPlanTest {
    private fun seg(id: String, w: Int, h: Int, start: Long = 0L, end: Long = 1_000_000L) =
        Segment(id, "content://$id", 5_000_000L, start, end, true, w, h)

    @Test
    fun p720LandscapeAndPortrait() {
        assertEquals(1280 to 720, outputSize(1920, 1080, Quality.P720))
        assertEquals(720 to 1280, outputSize(1080, 1920, Quality.P720))
    }

    @Test
    fun squareCountsAsLandscape() {
        assertEquals(1920 to 1080, outputSize(1000, 1000, Quality.P1080))
    }

    @Test
    fun p1080Portrait() {
        assertEquals(1080 to 1920, outputSize(720, 1280, Quality.P1080))
    }

    @Test
    fun matchFirstKeepsSmallSizes() {
        assertEquals(1280 to 720, outputSize(1280, 720, Quality.MATCH_FIRST))
    }

    @Test
    fun matchFirstCapsFourK() {
        assertEquals(1920 to 1080, outputSize(3840, 2160, Quality.MATCH_FIRST))
        assertEquals(1080 to 1920, outputSize(2160, 3840, Quality.MATCH_FIRST))
    }

    @Test
    fun matchFirstRoundsToEven() {
        assertEquals(640 to 360, outputSize(641, 361, Quality.MATCH_FIRST))
    }

    @Test
    fun planMapsSegmentsInOrderUsingFirstClipForSize() {
        val project = Project(
            "p", "P",
            listOf(seg("a", 1080, 1920, 500_000L, 900_000L), seg("b", 1920, 1080)),
            0L, 0L,
        )
        val plan = planComposition(project, Quality.P720)
        assertEquals(720 to 1280, plan.width to plan.height)
        assertEquals(
            listOf(ClipPlan("content://a", 500_000L, 900_000L), ClipPlan("content://b", 0L, 1_000_000L)),
            plan.clips,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyProjectThrows() {
        planComposition(Project("p", "P", emptyList(), 0L, 0L), Quality.P720)
    }
}
