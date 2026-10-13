package com.jjveleber.videoeditor.model

import kotlinx.serialization.Serializable

const val MIN_SEGMENT_US: Long = 100_000L

/** A kept range [startUs, endUs) of one source video. width/height are display size (rotation applied). */
@Serializable
data class Segment(
    val id: String,
    val sourceUri: String,
    val sourceDurationUs: Long,
    val startUs: Long,
    val endUs: Long,
    val hasAudio: Boolean,
    val width: Int,
    val height: Int,
) {
    val durationUs: Long get() = endUs - startUs
}

@Serializable
data class Project(
    val id: String,
    val name: String,
    val segments: List<Segment> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
)
