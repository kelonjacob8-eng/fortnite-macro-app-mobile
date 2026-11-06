package com.tapmacro.recorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.util.Log

/** Sends planned gestures through the accessibility service, one after another. */
object GestureSender {
    private const val TAG = "GestureSender"

    fun build(group: PlannedGroup): GestureDescription {
        val builder = GestureDescription.Builder()
        for (stroke in group.strokes) {
            val path = Path()
            stroke.points.forEachIndexed { i, p ->
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.startMs, stroke.durationMs))
        }
        return builder.build()
    }

    /**
     * Dispatches [group] and calls [onDone] once Android has finished (or cancelled) it.
     * Returns false if the gesture could not be sent at all; [onDone] is not called then.
     */
    fun send(
        service: AccessibilityService,
        group: PlannedGroup,
        handler: Handler,
        onDone: () -> Unit,
    ): Boolean {
        val gesture = try {
            build(group)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Skipping a gesture Android would not accept", e)
            return false
        }
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onDone()
            override fun onCancelled(gestureDescription: GestureDescription?) = onDone()
        }
        return service.dispatchGesture(gesture, callback, handler)
    }

    /** Sends [groups] in order, each after the previous one finished, then calls [onDone]. */
    fun sendAll(
        service: AccessibilityService,
        groups: List<PlannedGroup>,
        handler: Handler,
        onDone: () -> Unit,
    ) {
        fun step(i: Int) {
            if (i >= groups.size) {
                onDone()
                return
            }
            if (!send(service, groups[i], handler) { step(i + 1) }) step(i + 1)
        }
        step(0)
    }
}
