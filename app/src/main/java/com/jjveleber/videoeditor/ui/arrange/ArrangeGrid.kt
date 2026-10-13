package com.jjveleber.videoeditor.ui.arrange

import kotlin.math.floor

/** How many [cardWidth] cards, with [gap] between them, fit across [availableWidth]. Always at least one. */
fun gridColumns(availableWidth: Float, cardWidth: Float, gap: Float): Int =
    ((availableWidth + gap) / (cardWidth + gap)).toInt().coerceAtLeast(1)

/** Index of the card slot under ([x], [y]) in a row-major grid, clamped to the [count] cards. */
fun gridTargetIndex(x: Float, y: Float, columns: Int, cellWidth: Float, cellHeight: Float, count: Int): Int {
    val col = floor(x / cellWidth).toInt().coerceIn(0, columns - 1)
    val row = floor(y / cellHeight).toInt().coerceAtLeast(0)
    return (row * columns + col).coerceIn(0, count - 1)
}
