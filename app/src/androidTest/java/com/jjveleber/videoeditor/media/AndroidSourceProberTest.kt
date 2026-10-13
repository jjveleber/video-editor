package com.jjveleber.videoeditor.media

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jjveleber.videoeditor.copyAsset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidSourceProberTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prober = AndroidSourceProber(context)

    @Test
    fun probesLandscapeClipWithAudio() = runBlocking {
        val r = prober.probe(copyAsset("clip_landscape_audio.mp4"))!!
        assertEquals(640 to 360, r.width to r.height)
        assertEquals(true, r.hasAudio)
        assertEquals(2_000_000.0, r.durationUs.toDouble(), 100_000.0)
    }

    @Test
    fun probesSilentClip() = runBlocking {
        val r = prober.probe(copyAsset("clip_portrait_silent.mp4"))!!
        assertEquals(360 to 640, r.width to r.height)
        assertEquals(false, r.hasAudio)
    }

    @Test
    fun probesRotatedClipAsPortrait() = runBlocking {
        val r = prober.probe(copyAsset("clip_rotated.mp4"))!!
        assertEquals(360 to 640, r.width to r.height)
    }

    @Test
    fun nonVideoFileIsNull() = runBlocking {
        val f = File(context.cacheDir, "notes.txt").apply { writeText("hello") }
        assertNull(prober.probe(Uri.fromFile(f).toString()))
    }

    @Test
    fun thumbnailLoads() = runBlocking {
        val bmp = ThumbnailLoader(context).load(copyAsset("clip_landscape_audio.mp4"), 500_000L, 72)
        assertNotNull(bmp)
    }
}
