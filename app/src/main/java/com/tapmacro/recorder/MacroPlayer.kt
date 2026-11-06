package com.tapmacro.recorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.SystemClock

/**
 * Replays a macro on a loop with the timing it was recorded with.
 *
 * Each gesture is sent at its recorded offset from the start of the pass, but
 * never before the previous gesture has finished (sending a new gesture would
 * cancel the one still running).
 */
class MacroPlayer(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val onPassStarted: (pass: Int) -> Unit,
    private val onFinished: () -> Unit,
) {
    private val token = Any()
    private var groups: List<PlannedGroup> = emptyList()
    private var passDurationMs = 0L
    private var repeatCount = 0
    private var repeatDelayMs = 0L

    private var running = false
    private var generation = 0
    private var pass = 0
    private var index = 0
    private var passStart = 0L

    val isRunning: Boolean get() = running

    fun start(macro: Macro, repeatCount: Int, repeatDelayMs: Long, speed: Float) {
        stop()
        val screen = ScreenInfo.get(service)
        // Same orientation but a different resolution (e.g. the display size
        // setting changed): stretch the taps to match.
        val sameOrientation = macro.screenWidth > 0 && macro.screenHeight > 0 &&
            (macro.screenWidth > macro.screenHeight) == (screen.width > screen.height)
        val scaleX = if (sameOrientation) screen.width.toFloat() / macro.screenWidth else 1f
        val scaleY = if (sameOrientation) screen.height.toFloat() / macro.screenHeight else 1f

        groups = MacroPlanner.plan(
            strokes = macro.strokes,
            speed = speed,
            scaleX = scaleX,
            scaleY = scaleY,
            screenWidth = screen.width,
            screenHeight = screen.height,
            maxStrokesPerGesture = minOf(MacroPlanner.DEFAULT_MAX_STROKES, GestureDescription.getMaxStrokeCount()),
            maxGestureDurationMs = minOf(MacroPlanner.DEFAULT_MAX_DURATION_MS, GestureDescription.getMaxGestureDuration() - 500),
        )
        if (groups.isEmpty()) {
            onFinished()
            return
        }
        passDurationMs = MacroPlanner.loopDurationMs(macro, groups, speed)
        this.repeatCount = repeatCount
        this.repeatDelayMs = repeatDelayMs
        running = true
        generation++
        pass = 0
        startPass()
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(token)
    }

    private fun startPass() {
        pass++
        index = 0
        passStart = SystemClock.uptimeMillis()
        onPassStarted(pass)
        next()
    }

    private fun next() {
        if (!running) return
        val gen = generation

        if (index >= groups.size) {
            if (repeatCount > 0 && pass >= repeatCount) {
                running = false
                onFinished()
                return
            }
            val at = maxOf(SystemClock.uptimeMillis(), passStart + passDurationMs) + repeatDelayMs
            handler.postAtTime({ if (gen == generation) startPass() }, token, at)
            return
        }

        val group = groups[index]
        val at = passStart + group.startMs
        if (at > SystemClock.uptimeMillis()) {
            handler.postAtTime({ if (gen == generation) dispatch(group) }, token, at)
        } else {
            dispatch(group)
        }
    }

    private fun dispatch(group: PlannedGroup) {
        val gen = generation
        val sent = GestureSender.send(service, group, handler) {
            if (gen == generation) {
                index++
                next()
            }
        }
        if (!sent) {
            // Android refused this one (rare); skip it and keep going.
            index++
            handler.postAtTime({ if (gen == generation) next() }, token, SystemClock.uptimeMillis() + 50)
        }
    }
}
