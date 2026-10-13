package com.jjveleber.videoeditor.model

import java.util.UUID

fun Project.append(newSegments: List<Segment>): Project = copy(segments = segments + newSegments)

fun Project.trimStart(segmentId: String, startUs: Long): Project = mapSegment(segmentId) { s ->
    s.copy(startUs = startUs.coerceIn(0L, s.endUs - MIN_SEGMENT_US))
}

fun Project.trimEnd(segmentId: String, endUs: Long): Project = mapSegment(segmentId) { s ->
    s.copy(endUs = endUs.coerceIn(s.startUs + MIN_SEGMENT_US, s.sourceDurationUs))
}

fun Project.canSplitAt(globalUs: Long): Boolean {
    val pos = locate(globalUs) ?: return false
    val seg = segments[pos.index]
    return pos.offsetUs >= MIN_SEGMENT_US && seg.durationUs - pos.offsetUs >= MIN_SEGMENT_US
}

/** Splits the segment under [globalUs] into two. The right half gets a new id. */
fun Project.splitAt(globalUs: Long, newId: () -> String = { UUID.randomUUID().toString() }): Project {
    if (!canSplitAt(globalUs)) return this
    val pos = locate(globalUs)!!
    val seg = segments[pos.index]
    val cutUs = seg.startUs + pos.offsetUs
    val list = segments.toMutableList()
    list[pos.index] = seg.copy(endUs = cutUs)
    list.add(pos.index + 1, seg.copy(id = newId(), startUs = cutUs))
    return copy(segments = list)
}

fun Project.delete(segmentId: String): Project = copy(segments = segments.filterNot { it.id == segmentId })

/** Moves the segment at [from] so it ends up at index [to]. */
fun Project.move(from: Int, to: Int): Project {
    if (from !in segments.indices || to !in segments.indices || from == to) return this
    val list = segments.toMutableList()
    list.add(to, list.removeAt(from))
    return copy(segments = list)
}

private inline fun Project.mapSegment(id: String, transform: (Segment) -> Segment): Project =
    copy(segments = segments.map { if (it.id == id) transform(it) else it })
