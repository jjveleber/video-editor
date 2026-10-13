package com.jjveleber.videoeditor.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.videoeditor.export.ExportController
import com.jjveleber.videoeditor.history.UndoStack
import com.jjveleber.videoeditor.media.SourceProber
import com.jjveleber.videoeditor.media.toSegment
import com.jjveleber.videoeditor.model.MIN_SEGMENT_US
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.model.append
import com.jjveleber.videoeditor.model.delete
import com.jjveleber.videoeditor.model.move
import com.jjveleber.videoeditor.model.splitAt
import com.jjveleber.videoeditor.model.trimEnd
import com.jjveleber.videoeditor.model.trimStart
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.storage.UriGrants
import com.jjveleber.videoeditor.storage.releaseUnreferencedGrants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val project: Project? = null,
    val selectedId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val missingUris: Set<String> = emptySet(),
    val isDragging: Boolean = false,
    val message: String? = null,
) {
    val hasMissing: Boolean get() = project?.segments?.any { it.sourceUri in missingUris } == true
    val canExport: Boolean get() = project != null && project.segments.isNotEmpty() && !hasMissing
}

private const val SAVE_DEBOUNCE_MS = 500L

class EditorViewModel(
    private val projectId: String,
    private val repo: ProjectRepository,
    private val prober: SourceProber,
    private val grants: UriGrants,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var history: UndoStack<Project>? = null
    private var dragBase: Project? = null
    private var saveJob: Job? = null
    private var pendingSave: Project? = null

    init {
        viewModelScope.launch {
            val project = withContext(io) { repo.load(projectId) } ?: return@launch
            history = UndoStack(project)
            val missing = project.segments.map { it.sourceUri }.distinct()
                .filter { prober.probe(it) == null }
                .toSet()
            _state.update { it.copy(project = project, missingUris = missing) }
        }
    }

    fun addClips(uris: List<String>) {
        viewModelScope.launch {
            val added = mutableListOf<Segment>()
            val unusable = mutableListOf<String>()
            val notPersisted = mutableListOf<String>()
            for (uri in uris) {
                val result = prober.probe(uri)
                if (result == null || result.durationUs < MIN_SEGMENT_US) {
                    unusable += withContext(io) { prober.displayName(uri) }
                    continue
                }
                _state.update { it.copy(missingUris = it.missingUris - uri) }
                if (!grants.take(uri)) notPersisted += withContext(io) { prober.displayName(uri) }
                added += result.toSegment(uri)
            }
            if (added.isNotEmpty()) edit { it.append(added) }
            val messages = buildList {
                if (unusable.isNotEmpty()) add("Can't use ${unusable.joinToString()}")
                if (notPersisted.isNotEmpty()) add("${notPersisted.joinToString()} may be unavailable after restart")
            }
            if (messages.isNotEmpty()) _state.update { it.copy(message = messages.joinToString("\n")) }
        }
    }

    fun select(id: String?) = _state.update { it.copy(selectedId = id) }

    /** Live trim while dragging. Not recorded in history until [endTrim]. */
    fun trimStartPreview(segmentId: String, startUs: Long) = preview { it.trimStart(segmentId, startUs) }

    fun trimEndPreview(segmentId: String, endUs: Long) = preview { it.trimEnd(segmentId, endUs) }

    fun endTrim() {
        val base = dragBase ?: return
        dragBase = null
        _state.update { it.copy(isDragging = false) }
        val result = _state.value.project ?: return
        if (result != base) commit(result)
    }

    fun splitAt(globalUs: Long) = edit { it.splitAt(globalUs) }

    fun deleteSelected() {
        val id = _state.value.selectedId ?: return
        edit { it.delete(id) }
    }

    fun move(from: Int, to: Int) = edit { it.move(from, to) }

    fun undo() {
        history?.undo()?.let { publish(it); scheduleSave(it) }
    }

    fun redo() {
        history?.redo()?.let { publish(it); scheduleSave(it) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /** Writes any pending autosave now. Call when the screen stops. Blocking (small file write). */
    fun flush() {
        val pending = pendingSave ?: return
        saveJob?.cancel()
        pendingSave = null
        repo.save(pending)
    }

    override fun onCleared() {
        flush()
        // Safe to release now: the undo history (which could restore a deleted clip) dies with this screen.
        releaseUnreferencedGrants(repo.list(), grants, ExportController.activeUris)
    }

    private fun preview(op: (Project) -> Project) {
        val base = dragBase ?: history?.current ?: return
        dragBase = base
        _state.update { it.copy(project = op(base), isDragging = true) }
    }

    private fun edit(op: (Project) -> Project) {
        val current = history?.current ?: return
        val next = op(current)
        if (next != current) commit(next)
    }

    private fun commit(next: Project) {
        history?.push(next) ?: return
        publish(next)
        scheduleSave(next)
    }

    private fun publish(project: Project) {
        val h = history ?: return
        _state.update { s ->
            s.copy(
                project = project,
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                selectedId = s.selectedId?.takeIf { id -> project.segments.any { it.id == id } },
            )
        }
    }

    private fun scheduleSave(project: Project) {
        pendingSave = project
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val toSave = pendingSave ?: return@launch
            withContext(io) { repo.save(toSave) }
            if (pendingSave === toSave) pendingSave = null
        }
    }
}
