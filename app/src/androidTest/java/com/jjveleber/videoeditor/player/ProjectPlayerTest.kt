package com.jjveleber.videoeditor.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectPlayerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun seekToGlobalLandsInSecondSegment() {
        val uri = copyAsset("clip_landscape_audio.mp4")
        val project = Project(
            "p", "P",
            listOf(
                Segment("a", uri, 2_000_000L, 0L, 1_500_000L, true, 640, 360),
                Segment("b", uri, 2_000_000L, 500_000L, 2_000_000L, true, 640, 360),
            ),
            0L, 0L,
        )
        var index = -1
        var positionMs = -1L
        var globalUs = -1L
        instrumentation.runOnMainSync {
            val pp = ProjectPlayer(instrumentation.targetContext)
            pp.setProject(project)
            pp.seekToGlobal(2_000_000L)
            index = pp.player.currentMediaItemIndex
            positionMs = pp.player.currentPosition
            globalUs = pp.currentGlobalUs()
            pp.release()
        }
        assertEquals(1, index)
        assertEquals(500L, positionMs)
        assertEquals(2_000_000L, globalUs)
    }

    @Test
    fun nullProjectClearsPlaylist() {
        val uri = copyAsset("clip_landscape_audio.mp4")
        val project = Project(
            "p", "P",
            listOf(Segment("a", uri, 2_000_000L, 0L, 1_500_000L, true, 640, 360)),
            0L, 0L,
        )
        var before = -1
        var after = -1
        instrumentation.runOnMainSync {
            val pp = ProjectPlayer(instrumentation.targetContext)
            pp.setProject(project)
            before = pp.player.mediaItemCount
            pp.setProject(null)
            after = pp.player.mediaItemCount
            pp.release()
        }
        assertEquals(1, before)
        assertEquals(0, after)
    }
}
