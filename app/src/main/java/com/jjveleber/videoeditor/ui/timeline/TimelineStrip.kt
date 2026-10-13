package com.jjveleber.videoeditor.ui.timeline

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import kotlin.math.roundToInt

private val PX_PER_SECOND = 20.dp
private val MIN_BLOCK = 24.dp
private val HANDLE = 16.dp
private val STRIP_HEIGHT = 72.dp

@Composable
fun TimelineStrip(
    project: Project,
    missingUris: Set<String>,
    selectedId: String?,
    playheadUs: Long,
    isPlaying: Boolean,
    thumbnails: ThumbnailLoader,
    onSeek: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimDone: () -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val pxPerSecond = with(density) { PX_PER_SECOND.toPx() }
    val minBlockPx = with(density) { MIN_BLOCK.toPx() }
    val geometry = remember(project.segments, pxPerSecond, minBlockPx) {
        TimelineGeometry(project.segments.map { it.durationUs }, pxPerSecond, minBlockPx)
    }
    val currentGeometry by rememberUpdatedState(geometry)
    val scroll = rememberScrollState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val playheadX = geometry.globalUsToX(playheadUs)

    // Keep the playhead on screen during playback.
    LaunchedEffect(playheadX, isPlaying) {
        if (isPlaying && viewportPx > 0 && (playheadX < scroll.value || playheadX > scroll.value + viewportPx)) {
            scroll.scrollTo((playheadX - viewportPx * 0.2f).roundToInt().coerceAtLeast(0))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(STRIP_HEIGHT)
            .onSizeChanged { viewportPx = it.width }
            .horizontalScroll(scroll),
    ) {
        Box(
            Modifier
                .width(with(density) { geometry.totalWidth.toDp() })
                .fillMaxHeight()
                .pointerInput(Unit) { detectTapGestures(onTap = { onSeek(currentGeometry.xToGlobalUs(it.x)) }) },
        ) {
            project.segments.forEachIndexed { index, segment ->
                key(segment.id) {
                    SegmentBlock(
                        segment = segment,
                        index = index,
                        geometry = geometry,
                        selected = segment.id == selectedId,
                        missing = segment.sourceUri in missingUris,
                        thumbnails = thumbnails,
                        onSeek = onSeek,
                        onSelect = onSelect,
                        onTrimStart = onTrimStart,
                        onTrimEnd = onTrimEnd,
                        onTrimDone = onTrimDone,
                        onMove = onMove,
                    )
                }
            }
            Box(
                Modifier
                    .offset { IntOffset(playheadX.roundToInt(), 0) }
                    .zIndex(2f)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}

@Composable
private fun SegmentBlock(
    segment: Segment,
    index: Int,
    geometry: TimelineGeometry,
    selected: Boolean,
    missing: Boolean,
    thumbnails: ThumbnailLoader,
    onSeek: (Long) -> Unit,
    onSelect: (String) -> Unit,
    onTrimStart: (String, Long) -> Unit,
    onTrimEnd: (String, Long) -> Unit,
    onTrimDone: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val current by rememberUpdatedState(segment)
    val currentIndex by rememberUpdatedState(index)
    val geo by rememberUpdatedState(geometry)
    var dragX by remember { mutableFloatStateOf(0f) }
    val left = geometry.starts[index]
    val width = geometry.widths[index]
    val shape = RoundedCornerShape(4.dp)
    val heightPx = with(density) { STRIP_HEIGHT.roundToPx() }

    // Re-fetch the thumbnail only when the start moves to a different second, not on every drag frame.
    val thumbnail by produceState<Bitmap?>(null, segment.sourceUri, segment.startUs / 1_000_000, missing) {
        value = if (missing) null else thumbnails.load(segment.sourceUri, segment.startUs, heightPx)
    }

    Box(
        Modifier
            .offset { IntOffset((left + dragX).roundToInt(), 0) }
            .zIndex(if (dragX != 0f) 1f else 0f)
            .width(with(density) { width.toDp() })
            .fillMaxHeight()
            .padding(horizontal = 1.dp)
            .clip(shape)
            .background(if (missing) Color(0xFFB00020) else Color.DarkGray)
            .then(if (selected) Modifier.border(2.dp, Color.Yellow, shape) else Modifier)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { offset ->
                    onSelect(current.id)
                    onSeek(geo.xToGlobalUs(geo.starts[currentIndex] + offset.x))
                })
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        val to = reorderTargetIndex(geo.widths, currentIndex, dragX)
                        dragX = 0f
                        if (to != currentIndex) onMove(currentIndex, to)
                    },
                    onDragCancel = { dragX = 0f },
                ) { change, amount ->
                    change.consume()
                    dragX += amount.x
                }
            },
    ) {
        thumbnail?.let {
            Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        TrimHandle(
            alignment = Alignment.CenterStart,
            initialValue = { current.startUs },
            pxToUs = { geo.pxToUs(it) },
            onTrim = { onTrimStart(current.id, it) },
            onTrimDone = onTrimDone,
        )
        TrimHandle(
            alignment = Alignment.CenterEnd,
            initialValue = { current.endUs },
            pxToUs = { geo.pxToUs(it) },
            onTrim = { onTrimEnd(current.id, it) },
            onTrimDone = onTrimDone,
        )
    }
}

/** Drag handle on one edge of a block. [onTrim] receives the edge time at drag start plus the dragged delta. */
@Composable
private fun BoxScope.TrimHandle(
    alignment: Alignment,
    initialValue: () -> Long,
    pxToUs: (Float) -> Long,
    onTrim: (Long) -> Unit,
    onTrimDone: () -> Unit,
) {
    val currentInitial by rememberUpdatedState(initialValue)
    val currentPxToUs by rememberUpdatedState(pxToUs)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val currentDone by rememberUpdatedState(onTrimDone)
    Box(
        Modifier
            .align(alignment)
            .width(HANDLE)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.6f))
            .pointerInput(Unit) {
                var startAt = 0L
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { startAt = currentInitial(); total = 0f },
                    onDragEnd = { currentDone() },
                    onDragCancel = { currentDone() },
                ) { change, dx ->
                    change.consume()
                    total += dx
                    currentOnTrim(startAt + currentPxToUs(total))
                }
            },
    )
}
