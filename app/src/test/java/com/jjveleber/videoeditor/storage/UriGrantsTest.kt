package com.jjveleber.videoeditor.storage

import com.jjveleber.videoeditor.model.Project
import com.jjveleber.videoeditor.model.Segment
import com.jjveleber.videoeditor.testing.FakeGrants
import org.junit.Assert.assertEquals
import org.junit.Test

class UriGrantsTest {
    private fun seg(uri: String) = Segment(uri, uri, 1_000_000L, 0L, 1_000_000L, true, 1, 1)

    @Test
    fun releasesOnlyUnreferencedUris() {
        val grants = FakeGrants().apply { take("u1"); take("u2"); take("u3") }
        val projects = listOf(
            Project("p1", "P1", listOf(seg("u1")), 0L, 0L),
            Project("p2", "P2", listOf(seg("u1"), seg("u2")), 0L, 0L),
        )
        releaseUnreferencedGrants(projects, grants)
        assertEquals(listOf("u3"), grants.released)
    }

    @Test
    fun keepsUrisInAlsoKeep() {
        val grants = FakeGrants().apply { take("u1"); take("u2"); take("u3") }
        releaseUnreferencedGrants(listOf(Project("p1", "P1", listOf(seg("u1")), 0L, 0L)), grants, alsoKeep = setOf("u2"))
        assertEquals(listOf("u3"), grants.released)
    }

    @Test
    fun releasesNothingWhenProjectsAreUnknown() {
        val grants = FakeGrants().apply { take("u1") }
        releaseUnreferencedGrants(null, grants)
        assertEquals(emptyList<String>(), grants.released)
    }
}
