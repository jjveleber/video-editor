package com.jjveleber.videoeditor.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditsTest {
    private fun seg(id: String, startUs: Long = 0L, endUs: Long = 4_000_000L, sourceUs: Long = 10_000_000L) =
        Segment(id, "file:///$id.mp4", sourceUs, startUs, endUs, true, 1920, 1080)

    private fun project(vararg s: Segment) = Project("p", "P", s.toList(), 0L, 0L)

    private fun ids(p: Project) = p.segments.map { it.id }

    @Test
    fun appendAddsToEnd() {
        val p = project(seg("a")).append(listOf(seg("b"), seg("c")))
        assertEquals(listOf("a", "b", "c"), ids(p))
    }

    @Test
    fun trimStartMovesStart() {
        assertEquals(1_000_000L, project(seg("a")).trimStart("a", 1_000_000L).segments[0].startUs)
    }

    @Test
    fun trimStartClampsAtZero() {
        assertEquals(0L, project(seg("a", startUs = 1_000_000L)).trimStart("a", -500L).segments[0].startUs)
    }

    @Test
    fun trimStartKeepsMinimumLength() {
        assertEquals(3_900_000L, project(seg("a")).trimStart("a", 3_990_000L).segments[0].startUs)
    }

    @Test
    fun trimEndClampsToSource() {
        assertEquals(10_000_000L, project(seg("a")).trimEnd("a", 20_000_000L).segments[0].endUs)
    }

    @Test
    fun trimEndKeepsMinimumLength() {
        val p = project(seg("a", startUs = 1_000_000L)).trimEnd("a", 1_050_000L)
        assertEquals(1_100_000L, p.segments[0].endUs)
    }

    @Test
    fun trimUnknownIdIsNoOp() {
        val p = project(seg("a"))
        assertEquals(p, p.trimStart("zz", 5L))
        assertEquals(p, p.trimEnd("zz", 5L))
    }

    @Test
    fun splitInMiddleOfSecondSegment() {
        val p = project(seg("a"), seg("b")).splitAt(5_000_000L) { "n" }
        assertEquals(listOf("a", "b", "n"), ids(p))
        assertEquals(0L to 1_000_000L, p.segments[1].startUs to p.segments[1].endUs)
        assertEquals(1_000_000L to 4_000_000L, p.segments[2].startUs to p.segments[2].endUs)
    }

    @Test
    fun splitUsesSourceTimeForTrimmedSegment() {
        val p = project(seg("a", startUs = 2_000_000L, endUs = 6_000_000L)).splitAt(1_000_000L) { "n" }
        assertEquals(2_000_000L to 3_000_000L, p.segments[0].startUs to p.segments[0].endUs)
        assertEquals(3_000_000L to 6_000_000L, p.segments[1].startUs to p.segments[1].endUs)
    }

    @Test
    fun splitNearEdgeIsRejected() {
        val p = project(seg("a"))
        assertFalse(p.canSplitAt(50_000L))
        assertFalse(p.canSplitAt(3_950_000L))
        assertTrue(p.canSplitAt(100_000L))
        assertEquals(p, p.splitAt(50_000L))
        assertEquals(p, p.splitAt(3_950_000L))
    }

    @Test
    fun splitEmptyProjectIsNoOp() {
        val p = project()
        assertFalse(p.canSplitAt(0L))
        assertEquals(p, p.splitAt(0L))
    }

    @Test
    fun deleteRemovesSegment() {
        assertEquals(listOf("b"), ids(project(seg("a"), seg("b")).delete("a")))
    }

    @Test
    fun moveForwardAndBackward() {
        val p = project(seg("a"), seg("b"), seg("c"))
        assertEquals(listOf("b", "c", "a"), ids(p.move(0, 2)))
        assertEquals(listOf("c", "a", "b"), ids(p.move(2, 0)))
    }

    @Test
    fun moveInvalidIndexIsNoOp() {
        val p = project(seg("a"), seg("b"), seg("c"))
        assertEquals(p, p.move(0, 3))
        assertEquals(p, p.move(-1, 0))
        assertEquals(p, p.move(1, 1))
    }
}
