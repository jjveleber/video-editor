package com.jjveleber.videoeditor.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val percent: Int) : ExportState
    data class Done(val uri: String) : ExportState
    data class Failed(val message: String) : ExportState
}

/** Process-wide export state shared by ExportService (writer) and the export sheet (reader). */
object ExportController {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    @Volatile
    var activeUris: Set<String> = emptySet()
        private set

    /** Marks an export of [uris] as running; their grants must not be released until it finishes. */
    fun begin(uris: Set<String>) {
        activeUris = uris
        _state.value = ExportState.Running(0)
    }

    fun update(state: ExportState) {
        if (state !is ExportState.Running) activeUris = emptySet()
        _state.value = state
    }

    fun reset() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }
}
