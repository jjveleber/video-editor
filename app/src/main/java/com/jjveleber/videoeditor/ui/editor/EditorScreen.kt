package com.jjveleber.videoeditor.ui.editor

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.jjveleber.videoeditor.media.ThumbnailLoader
import com.jjveleber.videoeditor.model.canSplitAt
import com.jjveleber.videoeditor.player.ProjectPlayer
import com.jjveleber.videoeditor.ui.arrange.ArrangeDialog
import com.jjveleber.videoeditor.ui.timeline.TimelineStrip
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(vm: EditorViewModel, thumbnails: ThumbnailLoader, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val projectPlayer = remember { ProjectPlayer(context) }
    var playheadUs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
    var showArrange by rememberSaveable { mutableStateOf(false) }
    val project = state.project

    DisposableEffect(Unit) { onDispose { projectPlayer.release() } }

    // Rebuild the preview after each committed edit (not on every trim-drag frame).
    LaunchedEffect(project?.segments, state.isDragging, state.hasMissing) {
        if (!state.isDragging) projectPlayer.setProject(if (state.hasMissing) null else project)
    }

    LaunchedEffect(Unit) {
        while (true) {
            playheadUs = projectPlayer.currentGlobalUs()
            isPlaying = projectPlayer.player.isPlaying
            delay(50)
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        projectPlayer.player.pause()
        vm.flush()
    }

    state.message?.let { message ->
        LaunchedEffect(message) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.addClips(uris.map { it.toString() })
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(project?.name.orEmpty(), color = Color.White, modifier = Modifier.padding(horizontal = 8.dp))
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { picker.launch(arrayOf("video/*")) }, enabled = project != null) { Text("Add") }
            TextButton(
                onClick = { vm.splitAt(playheadUs) },
                enabled = project?.canSplitAt(playheadUs) == true && !state.hasMissing,
            ) { Text("Split") }
            TextButton(onClick = vm::deleteSelected, enabled = state.selectedId != null) { Text("Delete") }
            TextButton(onClick = { projectPlayer.player.pause(); showArrange = true }, enabled = (project?.segments?.size ?: 0) >= 2) { Text("Arrange") }
            TextButton(onClick = vm::undo, enabled = state.canUndo) { Text("Undo") }
            TextButton(onClick = vm::redo, enabled = state.canRedo) { Text("Redo") }
            TextButton(onClick = { showExport = true }, enabled = state.canExport) { Text("Export") }
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { PlayerView(it).apply { player = projectPlayer.player } },
                modifier = Modifier.fillMaxSize(),
            )
            val notice = when {
                state.hasMissing -> "Some clips are missing (shown in red). Delete them to preview and export."
                project != null && project.segments.isEmpty() -> "Tap Add to add clips"
                else -> null
            }
            notice?.let { Text(it, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp)) }
        }

        if (project != null) {
            TimelineStrip(
                project = project,
                missingUris = state.missingUris,
                selectedId = state.selectedId,
                playheadUs = playheadUs,
                isPlaying = isPlaying,
                thumbnails = thumbnails,
                onSeek = projectPlayer::seekToGlobal,
                onSelect = vm::select,
                onTrimStart = vm::trimStartPreview,
                onTrimEnd = vm::trimEndPreview,
                onTrimDone = vm::endTrim,
                onMove = vm::move,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }

    // Undo can drop below two clips while Arrange is open; there is nothing left to arrange then.
    LaunchedEffect(project?.segments?.size) {
        if ((project?.segments?.size ?: 0) < 2) showArrange = false
    }

    if (showArrange && project != null && project.segments.size >= 2) {
        ArrangeDialog(
            project = project,
            missingUris = state.missingUris,
            selectedId = state.selectedId,
            canUndo = state.canUndo,
            thumbnails = thumbnails,
            onSelect = vm::select,
            onMove = vm::move,
            onMoveSelected = vm::moveSelected,
            onUndo = vm::undo,
            onDismiss = { showArrange = false },
        )
    }

    if (showExport && project != null) {
        ExportSheet(project, onDismiss = { showExport = false })
    }
}
