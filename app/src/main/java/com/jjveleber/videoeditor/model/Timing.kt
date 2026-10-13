package com.jjveleber.videoeditor.model

/** A position inside the project: segment index plus offset from that segment's start. */
data class SegmentPosition(val index: Int, val offsetUs: Long)

val Project.totalDurationUs: Long get() = segments.sumOf { it.durationUs }

fun Project.startOfSegmentUs(index: Int): Long = segments.take(index).sumOf { it.durationUs }

/** Maps a project-global time to a segment. A boundary belongs to the next segment; out-of-range clamps. */
fun Project.locate(globalUs: Long): SegmentPosition? {
    if (segments.isEmpty()) return null
    var start = 0L
    segments.forEachIndexed { i, s ->
        val end = start + s.durationUs
        if (globalUs < end) return SegmentPosition(i, (globalUs - start).coerceAtLeast(0L))
        start = end
    }
    return SegmentPosition(segments.lastIndex, segments.last().durationUs)
}
