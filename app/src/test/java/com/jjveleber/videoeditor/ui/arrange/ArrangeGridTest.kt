package com.jjveleber.videoeditor.ui.arrange

import org.junit.Assert.assertEquals
import org.junit.Test

class ArrangeGridTest {
    // 3 columns of 100 x 80 px cells, 7 cards (rows: 0 1 2 / 3 4 5 / 6).
    private fun target(x: Float, y: Float) = gridTargetIndex(x, y, columns = 3, cellWidth = 100f, cellHeight = 80f, count = 7)

    @Test
    fun pointInsideCellMapsToThatIndex() {
        assertEquals(0, target(10f, 10f))
        assertEquals(2, target(250f, 10f))
        assertEquals(4, target(150f, 100f))
        assertEquals(6, target(50f, 200f))
    }

    @Test
    fun pointOutsideGridClampsToNearestCard() {
        assertEquals(0, target(-50f, -50f))
        assertEquals(5, target(900f, 100f))
        // Empty slots after the last card resolve to the last card.
        assertEquals(6, target(250f, 200f))
        assertEquals(6, target(50f, 900f))
    }

    @Test
    fun columnsFitWidthWithAtLeastOne() {
        assertEquals(6, gridColumns(availableWidth = 1248f, cardWidth = 176f, gap = 12f))
        // Two cards and one gap fit exactly: no trailing gap is needed.
        assertEquals(2, gridColumns(availableWidth = 364f, cardWidth = 176f, gap = 12f))
        assertEquals(1, gridColumns(availableWidth = 120f, cardWidth = 176f, gap = 12f))
    }
}
