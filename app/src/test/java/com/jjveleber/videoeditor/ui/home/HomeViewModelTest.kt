package com.jjveleber.videoeditor.ui.home

import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.storage.ProjectRepository
import com.jjveleber.videoeditor.testing.FakeGrants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @get:Rule val tmp = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repo: ProjectRepository
    private val grants = FakeGrants()

    private fun seg(uri: String) = Segment(uri, uri, 2_000_000L, 0L, 2_000_000L, true, 1920, 1080)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repo = ProjectRepository(tmp.newFolder("projects"))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun createAddsProjectAndReturnsId() = runTest(dispatcher) {
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        var opened: String? = null
        vm.create { opened = it }
        runCurrent()
        assertEquals(1, vm.projects.value.size)
        assertEquals("Project 1", vm.projects.value[0].name)
        assertEquals(vm.projects.value[0].id, opened)
    }

    @Test
    fun summaryShowsCountAndDuration() = runTest(dispatcher) {
        repo.save(repo.create("A").copy(segments = listOf(seg("u1"), seg("u2"))))
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.refresh()
        runCurrent()
        val s = vm.projects.value.single()
        assertEquals(2, s.segmentCount)
        assertEquals(4_000_000L, s.totalDurationUs)
    }

    @Test
    fun renameUpdatesListAndIgnoresBlank() = runTest(dispatcher) {
        val id = repo.create("A").id
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.rename(id, "Trip")
        runCurrent()
        assertEquals("Trip", vm.projects.value.single().name)
        vm.rename(id, "   ")
        runCurrent()
        assertEquals("Trip", vm.projects.value.single().name)
    }

    @Test
    fun deleteReleasesOnlyUnreferencedGrants() = runTest(dispatcher) {
        grants.take("u1")
        grants.take("u2")
        repo.save(repo.create("P1").copy(segments = listOf(seg("u1"))))
        val p2 = repo.save(repo.create("P2").copy(segments = listOf(seg("u1"), seg("u2"))))
        val vm = HomeViewModel(repo, grants, io = dispatcher)
        vm.delete(p2.id)
        runCurrent()
        assertEquals(listOf("u2"), grants.released)
        assertEquals(listOf("P1"), vm.projects.value.map { it.name })
    }
}
