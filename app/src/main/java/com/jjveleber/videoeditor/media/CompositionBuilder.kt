package com.jjveleber.videoeditor.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects

/** One sequence, one clipped item per segment, all scaled to fit (letterboxed) the output size. */
@OptIn(UnstableApi::class)
fun buildComposition(plan: CompositionPlan): Composition {
    val fit = Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT)
    val items = plan.clips.map { clip ->
        val mediaItem = MediaItem.Builder()
            .setUri(clip.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clip.startUs / 1000)
                    .setEndPositionMs(clip.endUs / 1000)
                    .build(),
            )
            .build()
        EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList(), listOf(fit)))
            .build()
    }
    return Composition.Builder(EditedMediaItemSequence(items))
        .experimentalSetForceAudioTrack(true)
        .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        .build()
}
