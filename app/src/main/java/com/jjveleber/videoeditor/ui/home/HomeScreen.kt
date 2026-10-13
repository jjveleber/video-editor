package com.jjveleber.videoeditor.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jjveleber.videoeditor.ui.formatDuration
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: HomeViewModel, onOpen: (String) -> Unit) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    var menuFor by remember { mutableStateOf<ProjectSummary?>(null) }

    LifecycleResumeEffect(Unit) {
        vm.refresh()
        onPauseOrDispose { }
    }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { vm.create(onOpen) }) { Text("New project") }
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No projects yet")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(projects, key = { it.id }) { p ->
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = {
                            val edited = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                .format(Date(p.updatedAt))
                            Text("${p.segmentCount} clips, ${formatDuration(p.totalDurationUs)}, edited $edited")
                        },
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpen(p.id) },
                            onLongClick = { menuFor = p },
                        ),
                    )
                }
            }
        }
    }

    menuFor?.let { p ->
        ProjectMenuDialog(
            project = p,
            onRename = { vm.rename(p.id, it); menuFor = null },
            onDelete = { vm.delete(p.id); menuFor = null },
            onDismiss = { menuFor = null },
        )
    }
}

@Composable
private fun ProjectMenuDialog(
    project: ProjectSummary,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(project.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete ${project.name}?") },
            text = { Text("This removes the project. Your original videos are not touched. This can't be undone.") },
            confirmButton = { TextButton(onClick = onDelete) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Delete project", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
