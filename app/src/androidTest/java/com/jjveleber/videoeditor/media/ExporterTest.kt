package com.jjveleber.videoeditor.media

import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExporterTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mergesMixedClipsAt720p() = runBlocking {
        val landscape = copyAsset("clip_landscape_audio.mp4")
        val portraitSilent = copyAsset("clip_portrait_silent.mp4")
        val project = Project(
            "p", "test",
            listOf(
                Segment("a", landscape, 2_000_000L, 0L, 1_500_000L, true, 640, 360),
                Segment("b", portraitSilent, 2_000_000L, 0L, 1_500_000L, false, 360, 640),
            ),
            0L, 0L,
        )
        val uri = Exporter(context).export(planComposition(project, Quality.P720)) {}
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toDouble()
            assertEquals(3000.0, durationMs, 100.0)
            assertEquals("1280", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("720", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
        } finally {
            r.release()
            context.contentResolver.delete(uri, null, null)
        }
    }
}
