package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1L
    private lateinit var dir: File
    private lateinit var repo: ProjectRepository

    private val segment = Segment("s1", "content://a.mp4", 5_000_000L, 0L, 5_000_000L, true, 1920, 1080)

    @Before
    fun setUp() {
        dir = tmp.newFolder("projects")
        repo = ProjectRepository(dir) { now }
    }

    @Test
    fun createThenLoadRoundTrips() {
        val p = repo.create("A")
        repo.save(p.copy(segments = listOf(segment)))
        val loaded = repo.load(p.id)!!
        assertEquals("A", loaded.name)
        assertEquals(listOf(segment), loaded.segments)
    }

    @Test
    fun saveUpdatesTimestamp() {
        val p = repo.create("A")
        now = 5L
        repo.save(p)
        assertEquals(5L, repo.load(p.id)!!.updatedAt)
        assertEquals(1L, repo.load(p.id)!!.createdAt)
    }

    @Test
    fun listSortsByMostRecent() {
        val a = repo.create("A")
        now = 2L
        repo.create("B")
        now = 3L
        repo.save(a)
        assertEquals(listOf("A", "B"), repo.list().map { it.name })
    }

    @Test
    fun renameChangesName() {
        val p = repo.create("A")
        repo.rename(p.id, "Trip")
        assertEquals("Trip", repo.load(p.id)!!.name)
    }

    @Test
    fun deleteRemovesProject() {
        val p = repo.create("A")
        repo.delete(p.id)
        assertNull(repo.load(p.id))
        assertEquals(0, repo.list().size)
    }

    @Test
    fun loadMissingReturnsNull() {
        assertNull(repo.load("nope"))
    }

    @Test
    fun corruptFileIsSkipped() {
        repo.create("Good")
        File(dir, "broken.json").writeText("{not json")
        File(dir, "half.json.tmp").writeText("{\"id\":")
        assertEquals(listOf("Good"), repo.list().map { it.name })
        assertNull(repo.load("broken"))
    }
}
