package com.jjveleber.videoeditor.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailLoader(private val context: Context) {
    private val cache = LruCache<String, Bitmap>(64)

    suspend fun load(uri: String, timeUs: Long, heightPx: Int): Bitmap? {
        val key = "$uri@$timeUs@$heightPx"
        cache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, heightPx * 16 / 9, heightPx,
                )
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
        bitmap?.let { cache.put(key, it) }
        return bitmap
    }
}
