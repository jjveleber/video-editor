package com.jjveleber.videoeditor.ui.editor

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjveleber.videoeditor.export.ExportController
import com.jjveleber.videoeditor.export.ExportService
import com.jjveleber.videoeditor.export.ExportState
import com.jjveleber.videoeditor.media.Quality
import com.jjveleber.videoeditor.media.planComposition
import com.jjveleber.videoeditor.model.Project

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(project: Project, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val exportState by ExportController.state.collectAsStateWithLifecycle()
    var quality by remember { mutableStateOf(Quality.P1080) }
    val close = {
        ExportController.reset()
        onDismiss()
    }
    val start = { ExportService.start(context, planComposition(project, quality)) }

    ModalBottomSheet(onDismissRequest = close) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Export", style = MaterialTheme.typography.titleLarge)
            when (val s = exportState) {
                ExportState.Idle -> {
                    Quality.entries.forEach { q ->
                        Row(
                            Modifier.fillMaxWidth().clickable { quality = q },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = q == quality, onClick = { quality = q })
                            Text(q.label)
                        }
                    }
                    Button(onClick = start) { Text("Start") }
                }
                is ExportState.Running -> {
                    LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("${s.percent}%")
                    Text("You can leave the app. Export continues in the background.")
                }
                is ExportState.Done -> {
                    Text("Saved to Movies/VideoEditor/")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Uri.parse(s.uri), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                Toast.makeText(context, "No app to play the video", Toast.LENGTH_SHORT).show()
                            }
                        }) { Text("Open") }
                        TextButton(onClick = close) { Text("Close") }
                    }
                }
                is ExportState.Failed -> {
                    Text("Export failed: ${s.message}", color = MaterialTheme.colorScheme.error)
                    Button(onClick = start) { Text("Retry") }
                }
            }
        }
    }
}
