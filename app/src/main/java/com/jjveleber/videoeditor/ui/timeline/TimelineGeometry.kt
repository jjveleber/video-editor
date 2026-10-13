package com.jjveleber.videoeditor.ui.timeline

/**
 * Lays segments out left to right at [pxPerSecond], with each block at least [minBlockPx] wide.
 * Because of the minimum, x and time are mapped per block, not with one global scale.
 */
class TimelineGeometry(
    private val durationsUs: List<Long>,
    private val pxPerSecond: Float,
    minBlockPx: Float,
) {
    val widths: List<Float> = durationsUs.map { maxOf(it / 1_000_000f * pxPerSecond, minBlockPx) }
    val starts: List<Float> = widths.runningFold(0f) { acc, w -> acc + w }.dropLast(1)
    val totalWidth: Float = widths.sum()
    private val startsUs: List<Long> = durationsUs.runningFold(0L) { acc, d -> acc + d }.dropLast(1)

    /** Converts a drag distance to a time delta at the nominal scale. */
    fun pxToUs(px: Float): Long = (px.toDouble() / pxPerSecond * 1_000_000.0).toLong()

    fun xToGlobalUs(x: Float): Long {
        if (widths.isEmpty()) return 0L
        val i = starts.indexOfLast { it <= x }.coerceAtLeast(0)
        val frac = ((x - starts[i]) / widths[i]).coerceIn(0f, 1f)
        return startsUs[i] + (frac.toDouble() * durationsUs[i]).toLong()
    }

    fun globalUsToX(us: Long): Float {
        if (widths.isEmpty()) return 0f
        val i = startsUs.indexOfLast { it <= us }.coerceAtLeast(0)
        val frac = ((us - startsUs[i]).toDouble() / durationsUs[i]).coerceIn(0.0, 1.0)
        return starts[i] + (frac * widths[i]).toFloat()
    }
}

/** Index the dragged block should move to: the number of other blocks whose center is left of its center. */
fun reorderTargetIndex(widths: List<Float>, from: Int, dragX: Float): Int {
    var x = 0f
    val centers = widths.map { w -> (x + w / 2).also { x += w } }
    val dragged = centers[from] + dragX
    return centers.indices.count { it != from && centers[it] < dragged }
}
