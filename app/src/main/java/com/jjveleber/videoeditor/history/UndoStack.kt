package com.jjveleber.videoeditor.history

class UndoStack<T>(initial: T, private val capacity: Int = 50) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()

    var current: T = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun push(value: T) {
        past.addLast(current)
        if (past.size > capacity) past.removeFirst()
        current = value
        future.clear()
    }

    fun undo(): T? {
        if (past.isEmpty()) return null
        future.addLast(current)
        current = past.removeLast()
        return current
    }

    fun redo(): T? {
        if (future.isEmpty()) return null
        past.addLast(current)
        current = future.removeLast()
        return current
    }
}
