package com.tapmacro.recorder

import kotlin.math.max
import kotlin.math.roundToLong

data class PlanPoint(val x: Float, val y: Float)

/** A stroke ready to hand to Android. [startMs] is relative to its group's start. */
data class PlannedStroke(val startMs: Long, val durationMs: Long, val points: List<PlanPoint>) {
    val endMs: Long get() = startMs + durationMs
}

/**
 * Strokes that overlap in time (several fingers down at once) have to be sent
 * to Android as one gesture, because starting a new gesture cancels the one in
 * progress. [startMs] is relative to the start of the macro.
 */
data class PlannedGroup(val startMs: Long, val strokes: List<PlannedStroke>) {
    val durationMs: Long get() = strokes.maxOf { it.endMs }
    val endMs: Long get() = startMs + durationMs
}

/** Turns recorded strokes into the gestures that playback sends, applying speed and screen scaling. */
object MacroPlanner {
    /** Android allows up to 20 strokes per gesture; stay comfortably under. */
    const val DEFAULT_MAX_STROKES = 10

    /** Android caps a single gesture at 60 s. */
    const val DEFAULT_MAX_DURATION_MS = 59_000L

    fun plan(
        strokes: List<Stroke>,
        speed: Float = 1f,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        screenWidth: Int = 0,
        screenHeight: Int = 0,
        maxStrokesPerGesture: Int = DEFAULT_MAX_STROKES,
        maxGestureDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    ): List<PlannedGroup> {
        val factor = if (speed > 0f) speed.toDouble() else 1.0

        val scaled = strokes
            .filter { it.points.isNotEmpty() }
            .map { stroke ->
                val points = ArrayList<PlanPoint>(stroke.points.size)
                for (p in stroke.points) {
                    val pp = PlanPoint(clamp(p.x * scaleX, screenWidth), clamp(p.y * scaleY, screenHeight))
                    // Repeated positions add nothing to a path; drop them.
                    if (points.isEmpty() || points.last() != pp) points += pp
                }
                PlannedStroke(
                    startMs = (stroke.startMs / factor).roundToLong().coerceAtLeast(0L),
                    durationMs = (stroke.durationMs / factor).roundToLong().coerceIn(1L, maxGestureDurationMs),
                    points = points,
                )
            }
            .sortedBy { it.startMs }

        val groups = ArrayList<PlannedGroup>()
        var current = ArrayList<PlannedStroke>()
        var groupStart = 0L
        var groupEnd = 0L

        for (stroke in scaled) {
            val fits = current.isNotEmpty() &&
                stroke.startMs < groupEnd &&
                current.size < maxStrokesPerGesture &&
                max(groupEnd, stroke.endMs) - groupStart <= maxGestureDurationMs
            if (!fits && current.isNotEmpty()) {
                groups += finishGroup(groupStart, current)
                current = ArrayList()
            }
            if (current.isEmpty()) {
                groupStart = stroke.startMs
                groupEnd = stroke.endMs
            } else {
                groupEnd = max(groupEnd, stroke.endMs)
            }
            current += stroke
        }
        if (current.isNotEmpty()) groups += finishGroup(groupStart, current)
        return groups
    }

    /** How long one pass of the macro lasts at [speed], never shorter than its last gesture. */
    fun loopDurationMs(macro: Macro, groups: List<PlannedGroup>, speed: Float): Long {
        val factor = if (speed > 0f) speed.toDouble() else 1.0
        val recorded = (macro.durationMs / factor).roundToLong()
        return max(recorded, groups.lastOrNull()?.endMs ?: 0L)
    }

    private fun finishGroup(groupStart: Long, strokes: List<PlannedStroke>) =
        PlannedGroup(groupStart, strokes.map { it.copy(startMs = it.startMs - groupStart) })

    private fun clamp(v: Float, limit: Int): Float =
        if (limit > 0) v.coerceIn(0f, (limit - 1).toFloat()) else v.coerceAtLeast(0f)
}
