package com.jjveleber.videoeditor.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Exports to a temp file in cacheDir, then copies it into Movies/VideoEditor/. */
@OptIn(UnstableApi::class)
class Exporter(private val context: Context) {

    suspend fun export(plan: CompositionPlan, onProgress: (Int) -> Unit): Uri {
        val temp = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        try {
            withContext(Dispatchers.Main) { transform(buildComposition(plan), temp, onProgress) }
            return withContext(Dispatchers.IO) { saveToMovies(temp) }
        } finally {
            temp.delete()
        }
    }

    /** Must run on the main thread: Transformer is bound to the looper it is built on. */
    private suspend fun transform(composition: Composition, out: File, onProgress: (Int) -> Unit) = coroutineScope {
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()
        transformer.start(composition, out.absolutePath)
        val poller = launch {
            val holder = ProgressHolder()
            while (true) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                delay(250)
            }
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            throw e
        } finally {
            poller.cancel()
        }
    }

    private fun saveToMovies(file: File): Uri {
        val resolver = context.contentResolver
        val name = "VideoEditor_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoEditor")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Could not create output file")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open output file")
            output.use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
