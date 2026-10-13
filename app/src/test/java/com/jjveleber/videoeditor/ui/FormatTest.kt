package com.jjveleber.videoeditor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatsMinutesAndHours() {
        assertEquals("0:00", formatDuration(0L))
        assertEquals("1:05", formatDuration(65_000_000L))
        assertEquals("1:02:05", formatDuration(3_725_000_000L))
    }
}
