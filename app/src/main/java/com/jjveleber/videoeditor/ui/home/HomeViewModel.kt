package com.jjveleber.videoeditor.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjveleber.videoeditor.export.ExportController
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.totalDurationUs
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.storage.UriGrants
import com.jjveleber.videoeditor.storage.releaseUnreferencedGrants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProjectSummary(
    val id: String,
    val name: String,
    val segmentCount: Int,
    val totalDurationUs: Long,
    val updatedAt: Long,
)

private fun Project.toSummary() = ProjectSummary(id, name, segments.size, totalDurationUs, updatedAt)

class HomeViewModel(
    private val repo: ProjectRepository,
    private val grants: UriGrants,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _projects.value = loadSummaries()
        }
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val project = withContext(io) { repo.create("Project ${_projects.value.size + 1}") }
            _projects.value = loadSummaries()
            onCreated(project.id)
        }
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            withContext(io) { repo.rename(id, name.trim()) }
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            withContext(io) {
                repo.delete(id)
                releaseUnreferencedGrants(repo.list(), grants, ExportController.activeUris)
            }
            refresh()
        }
    }

    private suspend fun loadSummaries() = withContext(io) { repo.list().map { it.toSummary() } }
}
