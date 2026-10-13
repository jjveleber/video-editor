package com.jjveleber.videoeditor.export

import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.MainActivity
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.media.ClipPlan
import com.jjveleber.videoeditor.media.CompositionPlan
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceExportsAndReportsDone() = runBlocking {
        // Foreground the app so starting a foreground service is allowed.
        ActivityScenario.launch(MainActivity::class.java).use {
            ExportController.update(ExportState.Idle)
            val plan = CompositionPlan(1280, 720, listOf(ClipPlan(copyAsset("clip_landscape_audio.mp4"), 0L, 1_000_000L)))
            ExportService.start(context, plan)
            val result = withTimeout(120_000) {
                ExportController.state.first { it is ExportState.Done || it is ExportState.Failed }
            }
            assertTrue("got $result", result is ExportState.Done)
            context.contentResolver.delete(Uri.parse((result as ExportState.Done).uri), null, null)
            ExportController.reset()
        }
    }
}
