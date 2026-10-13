package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Project
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/** Stores each project as `<id>.json` in [dir]. Blocking I/O: call off the main thread. */
class ProjectRepository(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun create(name: String): Project {
        val now = clock()
        val project = Project(UUID.randomUUID().toString(), name, emptyList(), now, now)
        write(project)
        return project
    }

    fun list(): List<Project> = projectFiles().mapNotNull { read(it) }.sortedByDescending { it.updatedAt }

    /** Every project, or null if any project file could not be read, so the result may be incomplete. */
    fun listComplete(): List<Project>? {
        val files = projectFiles()
        return files.mapNotNull { read(it) }.takeIf { it.size == files.size }
    }

    fun load(id: String): Project? = read(fileFor(id))

    fun save(project: Project): Project {
        val saved = project.copy(updatedAt = clock())
        write(saved)
        return saved
    }

    fun rename(id: String, name: String): Project? = load(id)?.let { save(it.copy(name = name)) }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    private fun projectFiles() = dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()

    private fun fileFor(id: String) = File(dir, "$id.json")

    private fun read(file: File): Project? = try {
        if (file.exists()) json.decodeFromString<Project>(file.readText()) else null
    } catch (e: Exception) {
        null
    }

    /**
     * Writes to a unique temp file then renames, so a crash mid-write never leaves a half-written
     * project. Synchronized, with a per-write temp name, so concurrent saves cannot clobber each
     * other's temp file. The temp name does not end in `.json`, so [list] ignores it.
     */
    @Synchronized
    private fun write(project: Project) {
        dir.mkdirs()
        val tmp = File.createTempFile("${project.id}.", ".tmp", dir)
        try {
            tmp.writeText(json.encodeToString(project))
            if (!tmp.renameTo(fileFor(project.id))) throw IOException("Could not save project ${project.id}")
        } finally {
            tmp.delete()
        }
    }
}
