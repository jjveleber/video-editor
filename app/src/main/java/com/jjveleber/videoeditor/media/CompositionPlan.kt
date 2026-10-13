package com.jjveleber.videoeditor.media

import com.jjveleber.videoeditor.model.Project
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

enum class Quality(val label: String) {
    P720("720p"),
    P1080("1080p"),
    MATCH_FIRST("Match first clip"),
}

@Serializable
data class ClipPlan(val uri: String, val startUs: Long, val endUs: Long)

/** Everything needed to export, as plain data (serializable so it can be passed to the export service). */
@Serializable
data class CompositionPlan(val width: Int, val height: Int, val clips: List<ClipPlan>)

private const val MAX_LONG_SIDE = 1920

fun planComposition(project: Project, quality: Quality): CompositionPlan {
    require(project.segments.isNotEmpty()) { "Project has no clips" }
    val first = project.segments.first()
    val (width, height) = outputSize(first.width, first.height, quality)
    return CompositionPlan(width, height, project.segments.map { ClipPlan(it.sourceUri, it.startUs, it.endUs) })
}

fun outputSize(firstWidth: Int, firstHeight: Int, quality: Quality): Pair<Int, Int> {
    val portrait = firstHeight > firstWidth
    fun oriented(long: Int, short: Int) = if (portrait) short to long else long to short
    return when (quality) {
        Quality.P720 -> oriented(1280, 720)
        Quality.P1080 -> oriented(1920, 1080)
        Quality.MATCH_FIRST -> {
            val scale = minOf(1.0, MAX_LONG_SIDE.toDouble() / maxOf(firstWidth, firstHeight))
            even(firstWidth * scale) to even(firstHeight * scale)
        }
    }
}

/** Video encoders need even dimensions. */
private fun even(value: Double): Int = (value.roundToInt() / 2 * 2).coerceAtLeast(2)
