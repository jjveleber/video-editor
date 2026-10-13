package com.jjveleber.videoeditor.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {
    @Test
    fun startsWithInitialAndNothingToUndo() {
        val s = UndoStack(0)
        assertEquals(0, s.current)
        assertFalse(s.canUndo)
        assertFalse(s.canRedo)
        assertNull(s.undo())
        assertNull(s.redo())
    }

    @Test
    fun pushThenUndoRedo() {
        val s = UndoStack(0)
        s.push(1)
        s.push(2)
        assertEquals(1, s.undo())
        assertEquals(0, s.undo())
        assertNull(s.undo())
        assertEquals(1, s.redo())
        assertEquals(2, s.redo())
        assertNull(s.redo())
        assertEquals(2, s.current)
    }

    @Test
    fun pushClearsRedo() {
        val s = UndoStack(0)
        s.push(1)
        s.undo()
        s.push(5)
        assertFalse(s.canRedo)
        assertTrue(s.canUndo)
        assertEquals(5, s.current)
    }

    @Test
    fun capacityDropsOldest() {
        val s = UndoStack(0, capacity = 3)
        (1..5).forEach { s.push(it) }
        var steps = 0
        while (s.undo() != null) steps++
        assertEquals(3, steps)
        assertEquals(2, s.current)
    }

    @Test
    fun defaultCapacityKeepsFiftyUndos() {
        val s = UndoStack(0)
        (1..51).forEach { s.push(it) }
        var steps = 0
        while (s.undo() != null) steps++
        assertEquals(50, steps)
        assertEquals(1, s.current)
        assertFalse(s.canUndo)
    }
}
