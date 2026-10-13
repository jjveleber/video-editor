package com.jjveleber.videoeditor.export

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportControllerTest {
    @After
    fun tearDown() {
        ExportController.update(ExportState.Idle)
    }

    @Test
    fun resetDoesNotInterruptRunning() {
        ExportController.update(ExportState.Running(40))
        ExportController.reset()
        assertEquals(ExportState.Running(40), ExportController.state.value)
    }

    @Test
    fun resetClearsFinishedStates() {
        ExportController.update(ExportState.Done("content://x"))
        ExportController.reset()
        assertEquals(ExportState.Idle, ExportController.state.value)
        ExportController.update(ExportState.Failed("boom"))
        ExportController.reset()
        assertEquals(ExportState.Idle, ExportController.state.value)
    }
}
