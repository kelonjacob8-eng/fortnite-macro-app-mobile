package com.tapmacro.recorder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroPlannerTest {

    private fun tap(start: Long, x: Float, y: Float, dur: Long = 80) =
        Stroke(start, listOf(TouchPoint(0, x, y), TouchPoint(dur, x, y)))

    @Test
    fun separateTapsBecomeSeparateGestures() {
        val groups = MacroPlanner.plan(listOf(tap(1000, 10f, 20f), tap(2000, 30f, 40f)))
        assertEquals(2, groups.size)
        assertEquals(1000L, groups[0].startMs)
        assertEquals(2000L, groups[1].startMs)
        // A tap collapses to a single point and keeps its length.
        assertEquals(listOf(PlanPoint(10f, 20f)), groups[0].strokes.single().points)
        assertEquals(80L, groups[0].strokes.single().durationMs)
        assertEquals(0L, groups[0].strokes.single().startMs)
    }

    @Test
    fun overlappingFingersShareOneGesture() {
        val joystick = Stroke(
            500,
            listOf(TouchPoint(0, 100f, 100f), TouchPoint(500, 150f, 100f), TouchPoint(1500, 150f, 160f)),
        )
        val jump = tap(1200, 900f, 500f)
        val later = tap(3000, 1f, 1f)
        val groups = MacroPlanner.plan(listOf(later, jump, joystick))

        assertEquals(2, groups.size)
        val first = groups[0]
        assertEquals(500L, first.startMs)
        assertEquals(2, first.strokes.size)
        assertEquals(0L, first.strokes[0].startMs)
        assertEquals(700L, first.strokes[1].startMs) // 1200 - 500
        assertEquals(1500L, first.durationMs)
        assertEquals(3000L, groups[1].startMs)
    }

    @Test
    fun speedScalesTimesButNotPositions() {
        val groups = MacroPlanner.plan(listOf(tap(1000, 10f, 20f, dur = 100)), speed = 2f)
        assertEquals(500L, groups[0].startMs)
        assertEquals(50L, groups[0].strokes[0].durationMs)
        assertEquals(PlanPoint(10f, 20f), groups[0].strokes[0].points[0])
    }

    @Test
    fun pointsAreScaledAndClampedToScreen() {
        val groups = MacroPlanner.plan(
            listOf(tap(0, 500f, 1000f), tap(1000, -5f, 5000f)),
            scaleX = 2f,
            scaleY = 0.5f,
            screenWidth = 800,
            screenHeight = 600,
        )
        assertEquals(PlanPoint(799f, 500f), groups[0].strokes[0].points[0])
        assertEquals(PlanPoint(0f, 599f), groups[1].strokes[0].points[0])
    }

    @Test
    fun gesturesRespectStrokeAndDurationLimits() {
        // 12 fingers down at once: split to stay under the per-gesture stroke limit.
        val many = (0 until 12).map { tap(it * 10L, it.toFloat(), 0f, dur = 1000) }
        val groups = MacroPlanner.plan(many, maxStrokesPerGesture = 10)
        assertEquals(listOf(10, 2), groups.map { it.strokes.size })

        // A hold longer than Android allows gets trimmed.
        val hold = MacroPlanner.plan(listOf(tap(0, 1f, 1f, dur = 120_000)))
        assertEquals(MacroPlanner.DEFAULT_MAX_DURATION_MS, hold[0].strokes[0].durationMs)
    }

    @Test
    fun zeroLengthTapStillLastsAtLeastOneMs() {
        val groups = MacroPlanner.plan(listOf(Stroke(0, listOf(TouchPoint(0, 5f, 5f)))))
        assertEquals(1L, groups[0].strokes[0].durationMs)
    }

    @Test
    fun loopLengthIncludesIdleTailButNeverCutsALastGesture() {
        val strokes = listOf(tap(1000, 1f, 1f, dur = 100))
        val macro = Macro("id", "m", 0, 5000, 100, 100, 0, strokes)
        val groups = MacroPlanner.plan(strokes)
        assertEquals(5000L, MacroPlanner.loopDurationMs(macro, groups, 1f))
        assertEquals(2500L, MacroPlanner.loopDurationMs(macro, groups, 2f))

        val short = macro.copy(durationMs = 0)
        assertTrue(MacroPlanner.loopDurationMs(short, groups, 1f) >= 1100L)
    }
}
