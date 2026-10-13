package com.jjveleber.videoeditor.media

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.jjveleber.videoeditor.model.Segment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** What we need to know about a source video. width/height are display size (rotation applied). */
data class ProbeResult(val durationUs: Long, val width: Int, val height: Int, val hasAudio: Boolean)

fun ProbeResult.toSegment(uri: String, id: String = UUID.randomUUID().toString()): Segment =
    Segment(id, uri, durationUs, 0L, durationUs, hasAudio, width, height)

fun displaySize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
    if (rotationDegrees % 180 != 0) height to width else width to height

interface SourceProber {
    /** Returns null if the file is unreadable or has no video track. */
    suspend fun probe(uri: String): ProbeResult?
    fun displayName(uri: String): String
}

class AndroidSourceProber(private val context: Context) : SourceProber {
    override suspend fun probe(uri: String): ProbeResult? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            fun meta(key: Int) = retriever.extractMetadata(key)
            if (meta(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return@withContext null
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return@withContext null
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: return@withContext null
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: return@withContext null
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val (w, h) = displaySize(width, height, rotation)
            ProbeResult(
                durationUs = durationMs * 1000,
                width = w,
                height = h,
                hasAudio = meta(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
            )
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    override fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        val queried = try {
            context.contentResolver.query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
        return queried ?: parsed.lastPathSegment ?: uri
    }
}
