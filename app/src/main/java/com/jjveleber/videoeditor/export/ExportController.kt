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

    fun update(state: ExportState) {
        _state.value = state
    }

    fun reset() {
        if (_state.value !is ExportState.Running) _state.value = ExportState.Idle
    }
}
