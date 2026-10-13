package com.jjveleber.videoeditor.player

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.locate
import com.jjveleber.videoeditor.model.startOfSegmentUs
import com.jjveleber.videoeditor.model.totalDurationUs

/** Plays a project as a playlist of clipped items and maps player positions to project-global time. */
class ProjectPlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context).build()
    private var project: Project? = null

    fun setProject(project: Project?) {
        val keepUs = currentGlobalUs()
        this.project = project
        if (project == null || project.segments.isEmpty()) {
            player.clearMediaItems()
            return
        }
        val items = project.segments.map { s ->
            MediaItem.Builder()
                .setUri(s.sourceUri)
                .setMediaId(s.id)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(s.startUs / 1000)
                        .setEndPositionMs(s.endUs / 1000)
                        .build(),
                )
                .build()
        }
        val pos = project.locate(keepUs.coerceAtMost(project.totalDurationUs - 1))!!
        player.setMediaItems(items, pos.index, pos.offsetUs / 1000)
        player.prepare()
    }

    fun currentGlobalUs(): Long {
        val p = project ?: return 0L
        if (p.segments.isEmpty()) return 0L
        val index = player.currentMediaItemIndex.coerceIn(0, p.segments.lastIndex)
        return p.startOfSegmentUs(index) + player.currentPosition * 1000
    }

    fun seekToGlobal(us: Long) {
        val pos = project?.locate(us) ?: return
        player.seekTo(pos.index, pos.offsetUs / 1000)
    }

    fun release() {
        player.release()
    }
}
