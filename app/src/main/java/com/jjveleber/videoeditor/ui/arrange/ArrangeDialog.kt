package com.jjveleber.videoeditor.ui.arrange

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.ui.formatDuration
import kotlin.math.roundToInt

private val CARD_WIDTH = 176.dp
private val THUMB_HEIGHT = 99.dp
private val LABEL_HEIGHT = 32.dp
private val GAP = 12.dp

/** Full-screen view where every clip is a same-size card, so clip length doesn't affect reordering. */
@Composable
fun ArrangeDialog(
    project: Project,
    missingUris: Set<String>,
    selectedId: String?,
    canUndo: Boolean,
    thumbnails: ThumbnailLoader,
    onSelect: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMoveSelected: (Int) -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedIndex = project.segments.indexOfFirst { it.id == selectedId }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Arrange clips",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                TextButton(onClick = { onMoveSelected(-1) }, enabled = selectedIndex > 0) { Text("Move left") }
                TextButton(
                    onClick = { onMoveSelected(1) },
                    enabled = selectedIndex >= 0 && selectedIndex < project.segments.lastIndex,
                ) { Text("Move right") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onUndo, enabled = canUndo) { Text("Undo") }
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text(
                "Drag a card to a new spot, or select it and use Move.",
                color = Color.LightGray,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            CardGrid(project, missingUris, selectedId, thumbnails, onSelect, onMove)
        }
    }
}

@Composable
private fun CardGrid(
    project: Project,
    missingUris: Set<String>,
    selectedId: String?,
    thumbnails: ThumbnailLoader,
    onSelect: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val cellWidth = with(density) { (CARD_WIDTH + GAP).toPx() }
    val cellHeight = with(density) { (THUMB_HEIGHT + LABEL_HEIGHT + GAP).toPx() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    // Pointer position within the grid while dragging, and where on the card it grabbed.
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }

    BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        val columns = gridColumns(with(density) { maxWidth.toPx() }, with(density) { CARD_WIDTH.toPx() }, with(density) { GAP.toPx() })
        val count = project.segments.size
        val rows = (count + columns - 1) / columns
        // Derived so a drag frame only recomposes when the target slot changes.
        val target by remember(columns, count, cellWidth, cellHeight) {
            derivedStateOf { draggingId?.let { gridTargetIndex(pointer.x, pointer.y, columns, cellWidth, cellHeight, count) } }
        }
        fun origin(index: Int) = Offset((index % columns) * cellWidth, (index / columns) * cellHeight)

        Box(Modifier.height(with(density) { (rows * cellHeight).toDp() }).fillMaxWidth()) {
            target?.let {
                val o = origin(it)
                Box(
                    Modifier
                        .offset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                        // Above resting cards so the drop slot shows, below the dragged card.
                        .zIndex(0.5f)
                        .size(CARD_WIDTH, THUMB_HEIGHT + LABEL_HEIGHT)
                        .border(2.dp, Color.White, RoundedCornerShape(6.dp)),
                )
            }
            project.segments.forEachIndexed { index, segment ->
                key(segment.id) {
                    val currentIndex by rememberUpdatedState(index)
                    val dragging = segment.id == draggingId
                    ClipCard(
                        segment = segment,
                        position = index + 1,
                        selected = segment.id == selectedId,
                        missing = segment.sourceUri in missingUris,
                        thumbnails = thumbnails,
                        modifier = Modifier
                            .offset {
                                // Pointer is read here (layout), not in composition, so dragging doesn't recompose cards.
                                val o = if (dragging) pointer - grab else origin(index)
                                IntOffset(o.x.roundToInt(), o.y.roundToInt())
                            }
                            .zIndex(if (dragging) 1f else 0f)
                            .pointerInput(Unit) { detectTapGestures(onTap = { onSelect(segment.id) }) }
                            .pointerInput(columns, count) {
                                // Only one card drags at a time; a second finger on another card is ignored.
                                var mine = false
                                // detectDragGestures skips onDragCancel when this coroutine is cancelled (keys change
                                // or the card leaves composition, e.g. Undo mid-drag), so release the drag here too.
                                try {
                                    detectDragGestures(
                                        onDragStart = { down ->
                                            mine = draggingId == null
                                            if (!mine) return@detectDragGestures
                                            grab = down
                                            pointer = origin(currentIndex) + down
                                            draggingId = segment.id
                                            onSelect(segment.id)
                                        },
                                        onDragEnd = {
                                            if (!mine) return@detectDragGestures
                                            mine = false
                                            val to = gridTargetIndex(pointer.x, pointer.y, columns, cellWidth, cellHeight, count)
                                            draggingId = null
                                            if (to != currentIndex) onMove(currentIndex, to)
                                        },
                                        onDragCancel = {
                                            if (mine) draggingId = null
                                            mine = false
                                        },
                                    ) { change, amount ->
                                        change.consume()
                                        if (mine) pointer += amount
                                    }
                                } finally {
                                    if (mine) draggingId = null
                                }
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    segment: Segment,
    position: Int,
    selected: Boolean,
    missing: Boolean,
    thumbnails: ThumbnailLoader,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(6.dp)
    val heightPx = with(LocalDensity.current) { THUMB_HEIGHT.roundToPx() }
    val thumbnail by produceState<Bitmap?>(null, segment.sourceUri, segment.startUs, missing) {
        value = if (missing) null else thumbnails.load(segment.sourceUri, segment.startUs, heightPx)
    }
    Column(
        modifier
            .size(CARD_WIDTH, THUMB_HEIGHT + LABEL_HEIGHT)
            .clip(shape)
            .background(if (missing) Color(0xFFB00020) else Color.DarkGray)
            .then(if (selected) Modifier.border(3.dp, Color.Yellow, shape) else Modifier),
    ) {
        Box(Modifier.fillMaxWidth().height(THUMB_HEIGHT)) {
            thumbnail?.let {
                Image(it.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Row(
            Modifier.fillMaxWidth().height(LABEL_HEIGHT).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$position", color = Color.White, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(segment.durationUs), color = Color.White)
        }
    }
}
