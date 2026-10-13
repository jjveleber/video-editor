package com.jjveleber.videoeditor.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGeometryTest {
    // 1 s block is below the 24 px minimum (20 px/s), 2 s block is 40 px.
    private val g = TimelineGeometry(listOf(1_000_000L, 2_000_000L), pxPerSecond = 20f, minBlockPx = 24f)

    @Test
    fun widthsRespectMinimum() {
        assertEquals(listOf(24f, 40f), g.widths)
        assertEquals(listOf(0f, 24f), g.starts)
        assertEquals(64f, g.totalWidth, 0.001f)
    }

    @Test
    fun xToGlobalUsInterpolatesWithinBlock() {
        assertEquals(500_000L, g.xToGlobalUs(12f))
        assertEquals(2_000_000L, g.xToGlobalUs(44f))
        assertEquals(0L, g.xToGlobalUs(-10f))
        assertEquals(3_000_000L, g.xToGlobalUs(500f))
    }

    @Test
    fun globalUsToXIsInverse() {
        assertEquals(0f, g.globalUsToX(0L), 0.001f)
        assertEquals(12f, g.globalUsToX(500_000L), 0.001f)
        assertEquals(44f, g.globalUsToX(2_000_000L), 0.001f)
        assertEquals(64f, g.globalUsToX(9_000_000L), 0.001f)
    }

    @Test
    fun pxToUsUsesNominalScale() {
        assertEquals(500_000L, g.pxToUs(10f))
        assertEquals(-500_000L, g.pxToUs(-10f))
    }

    @Test
    fun emptyTimeline() {
        val empty = TimelineGeometry(emptyList(), 20f, 24f)
        assertEquals(0f, empty.totalWidth, 0.001f)
        assertEquals(0L, empty.xToGlobalUs(10f))
        assertEquals(0f, empty.globalUsToX(10L), 0.001f)
    }

    @Test
    fun reorderTargetFollowsDraggedCenter() {
        val widths = listOf(10f, 10f, 10f)
        assertEquals(1, reorderTargetIndex(widths, from = 0, dragX = 12f))
        assertEquals(2, reorderTargetIndex(widths, from = 0, dragX = 25f))
        assertEquals(0, reorderTargetIndex(widths, from = 2, dragX = -25f))
        assertEquals(1, reorderTargetIndex(widths, from = 1, dragX = 0f))
    }
}
