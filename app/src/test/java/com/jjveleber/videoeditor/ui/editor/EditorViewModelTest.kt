package com.jjveleber.videoeditor.ui.editor

import com.jjveleber.videoeditor.media.ProbeResult
import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.testing.FakeGrants
import com.jjveleber.videoeditor.testing.FakeProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class EditorViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: ProjectRepository
    private val grants = FakeGrants()
    private val good = ProbeResult(4_000_000L, 1920, 1080, true)
    private val prober = FakeProber(
        mapOf(
            "content://a.mp4" to good,
            "content://b.mp4" to good,
            "content://tiny.mp4" to ProbeResult(50_000L, 1920, 1080, true),
        ),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = ProjectRepository(tmp.newFolder("projects"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.newVm(project: Project = repo.create("P")): EditorViewModel {
        val vm = EditorViewModel(project.id, repo, prober, grants, io = dispatcher)
        runCurrent()
        return vm
    }

    private fun TestScope.vmWith(vararg uris: String): EditorViewModel {
        val vm = newVm()
        vm.addClips(uris.toList())
        runCurrent()
        return vm
    }

    @Test
    fun addClipsAppendsTakesGrantsAndAutosaves() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://b.mp4")
        val project = vm.state.value.project!!
        assertEquals(listOf("content://a.mp4", "content://b.mp4"), project.segments.map { it.sourceUri })
        assertEquals(setOf("content://a.mp4", "content://b.mp4"), grants.taken)
        assertTrue(vm.state.value.canUndo)
        assertEquals(0, repo.load(project.id)!!.segments.size)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(2, repo.load(project.id)!!.segments.size)
    }

    @Test
    fun unusableClipReportsMessageAndAddsTheRest() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://bad.mp4")
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertEquals("Can't use bad.mp4", vm.state.value.message)
        vm.consumeMessage()
        assertNull(vm.state.value.message)
    }

    @Test
    fun clipShorterThanMinimumIsUnusable() = runTest(dispatcher) {
        val vm = vmWith("content://tiny.mp4")
        assertEquals(0, vm.state.value.project!!.segments.size)
        assertEquals("Can't use tiny.mp4", vm.state.value.message)
    }

    @Test
    fun refusedGrantWarnsButStillAdds() = runTest(dispatcher) {
        grants.allowTake = false
        val vm = vmWith("content://a.mp4")
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertEquals("a.mp4 may be unavailable after restart", vm.state.value.message)
    }

    @Test
    fun undoAndRedoRestoreProjects() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.addClips(listOf("content://b.mp4"))
        runCurrent()
        vm.undo()
        assertEquals(1, vm.state.value.project!!.segments.size)
        vm.undo()
        assertEquals(0, vm.state.value.project!!.segments.size)
        assertFalse(vm.state.value.canUndo)
        vm.redo()
        assertEquals(1, vm.state.value.project!!.segments.size)
        assertTrue(vm.state.value.canRedo)
    }

    @Test
    fun trimDragIsOneUndoStep() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.segments[0].id
        vm.trimStartPreview(id, 500_000L)
        vm.trimStartPreview(id, 1_000_000L)
        assertTrue(vm.state.value.isDragging)
        assertEquals(1_000_000L, vm.state.value.project!!.segments[0].startUs)
        vm.endTrim()
        assertFalse(vm.state.value.isDragging)
        vm.undo()
        val segments = vm.state.value.project!!.segments
        assertEquals(1, segments.size)
        assertEquals(0L, segments[0].startUs)
    }

    @Test
    fun trimEndDragUpdatesEnd() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.segments[0].id
        vm.trimEndPreview(id, 3_000_000L)
        vm.endTrim()
        assertEquals(3_000_000L, vm.state.value.project!!.segments[0].endUs)
    }

    @Test
    fun splitAtPlayhead() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.splitAt(2_000_000L)
        assertEquals(2, vm.state.value.project!!.segments.size)
    }

    @Test
    fun deleteSelectedRemovesAndClearsSelection() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4", "content://b.mp4")
        vm.select(vm.state.value.project!!.segments[0].id)
        vm.deleteSelected()
        assertEquals(listOf("content://b.mp4"), vm.state.value.project!!.segments.map { it.sourceUri })
        assertNull(vm.state.value.selectedId)
    }

    @Test
    fun deletingSegmentDoesNotReleaseGrant() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        vm.select(vm.state.value.project!!.segments[0].id)
        vm.deleteSelected()
        advanceTimeBy(600)
        runCurrent()
        assertTrue(grants.released.isEmpty())
        vm.undo()
        assertEquals(1, vm.state.value.project!!.segments.size)
    }

    @Test
    fun missingSourceOnLoadBlocksExport() = runTest(dispatcher) {
        val gone = Segment("s1", "content://gone.mp4", 4_000_000L, 0L, 4_000_000L, true, 1920, 1080)
        val saved = repo.save(repo.create("P").copy(segments = listOf(gone)))
        val vm = newVm(saved)
        assertTrue(vm.state.value.hasMissing)
        assertFalse(vm.state.value.canExport)
        vm.select("s1")
        vm.deleteSelected()
        assertFalse(vm.state.value.hasMissing)
        vm.addClips(listOf("content://a.mp4"))
        runCurrent()
        assertTrue(vm.state.value.canExport)
    }

    @Test
    fun flushSavesImmediately() = runTest(dispatcher) {
        val vm = vmWith("content://a.mp4")
        val id = vm.state.value.project!!.id
        vm.flush()
        assertEquals(1, repo.load(id)!!.segments.size)
    }

    @Test
    fun readdingMissingSourceClearsMissingFlag() = runTest(dispatcher) {
        val flaky = FlakyProber(good)
        val project = repo.save(
            repo.create("P").copy(segments = listOf(Segment("s1", "content://a.mp4", 4_000_000L, 0L, 4_000_000L, true, 1920, 1080))),
        )
        val vm = EditorViewModel(project.id, repo, flaky, grants, io = dispatcher)
        runCurrent()
        assertTrue(vm.state.value.hasMissing)
        assertFalse(vm.state.value.canExport)
        flaky.available = true
        vm.addClips(listOf("content://a.mp4"))
        runCurrent()
        assertFalse(vm.state.value.hasMissing)
        assertTrue(vm.state.value.canExport)
    }

    private class FlakyProber(private val result: ProbeResult) : com.jjveleber.videoeditor.media.SourceProber {
        var available = false
        override suspend fun probe(uri: String): ProbeResult? = if (available) result else null
        override fun displayName(uri: String): String = uri.substringAfterLast('/')
    }
}
