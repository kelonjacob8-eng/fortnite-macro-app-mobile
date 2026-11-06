package com.tapmacro.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Transparent full-screen layer shown while recording. It catches every touch,
 * turns it into [Stroke]s and hands each finished gesture (all fingers lifted)
 * to [onGesture]. Strokes it emits use absolute uptime in [Stroke.startMs].
 *
 * Coordinates are taken from the raw screen position so they line up with what
 * Android expects when the taps are played back.
 */
@SuppressLint("ViewConstructor")
class CaptureView(
    context: Context,
    private val onGesture: (List<Stroke>) -> Unit,
) : View(context) {

    private class ActiveStroke(val downTime: Long, x: Float, y: Float) {
        val points = arrayListOf(TouchPoint(0L, x, y))
        var viewX = 0f
        var viewY = 0f
    }

    private class Marker(val x: Float, val y: Float, val number: Int)

    private val active = HashMap<Int, ActiveStroke>()
    private val finished = ArrayList<Stroke>()
    private val markers = ArrayDeque<Marker>()
    private var tapNumber = 0

    /** True while a recorded gesture is being sent through to the app underneath. */
    var forwarding = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val borderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4 * density
    }
    private val fingerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55FFFFFF }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3FF5252.toInt() }
    private val markerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        isFakeBoldText = true
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(13f)
        isFakeBoldText = true
        setShadowLayer(4 * density, 0f, 0f, Color.BLACK)
    }
    private val bannerText = context.getString(R.string.recording_banner)

    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // Offset from this view's coordinates to screen coordinates.
        val offX = e.rawX - e.x
        val offY = e.rawY - e.y

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active.clear()
                finished.clear()
                begin(e, e.actionIndex, offX, offY)
            }
            MotionEvent.ACTION_POINTER_DOWN -> begin(e, e.actionIndex, offX, offY)
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) {
                    for (i in 0 until e.pointerCount) {
                        move(
                            e.getPointerId(i), e.getHistoricalEventTime(h),
                            e.getHistoricalX(i, h), e.getHistoricalY(i, h), offX, offY,
                        )
                    }
                }
                for (i in 0 until e.pointerCount) {
                    move(e.getPointerId(i), e.eventTime, e.getX(i), e.getY(i), offX, offY)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> end(e, e.actionIndex, offX, offY)
            MotionEvent.ACTION_UP -> {
                end(e, e.actionIndex, offX, offY)
                val gesture = finished.toList()
                active.clear()
                finished.clear()
                if (gesture.isNotEmpty()) onGesture(gesture)
            }
            MotionEvent.ACTION_CANCEL -> {
                active.clear()
                finished.clear()
            }
        }
        invalidate()
        return true
    }

    private fun begin(e: MotionEvent, index: Int, offX: Float, offY: Float) {
        val x = e.getX(index)
        val y = e.getY(index)
        active[e.getPointerId(index)] = ActiveStroke(e.eventTime, x + offX, y + offY).apply {
            viewX = x
            viewY = y
        }
    }

    private fun move(id: Int, time: Long, x: Float, y: Float, offX: Float, offY: Float) {
        val stroke = active[id] ?: return
        stroke.viewX = x
        stroke.viewY = y
        val sx = x + offX
        val sy = y + offY
        val last = stroke.points.last()
        // Keep the path light: only record real movement.
        if (hypot(sx - last.x, sy - last.y) >= MIN_MOVE_PX) {
            stroke.points += TouchPoint((time - stroke.downTime).coerceAtLeast(0L), sx, sy)
        }
    }

    private fun end(e: MotionEvent, index: Int, offX: Float, offY: Float) {
        val stroke = active.remove(e.getPointerId(index)) ?: return
        val x = e.getX(index)
        val y = e.getY(index)
        // Always close the stroke with the lift-off time so taps and holds keep their length.
        val t = (e.eventTime - stroke.downTime).coerceAtLeast(1L)
        val last = stroke.points.last()
        if (last.t == t) stroke.points[stroke.points.size - 1] = TouchPoint(t, x + offX, y + offY)
        else stroke.points += TouchPoint(t, x + offX, y + offY)
        finished += Stroke(stroke.downTime, stroke.points)

        val first = stroke.points.first()
        markers.addLast(Marker(first.x - offX, first.y - offY, ++tapNumber))
        while (markers.size > MAX_MARKERS) markers.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        borderPaint.color = if (forwarding) 0x99FFFFFF.toInt() else 0xE6FF5252.toInt()
        val half = borderPaint.strokeWidth / 2
        canvas.drawRect(half, half, width - half, height - half, borderPaint)

        canvas.drawText(bannerText, width / 2f, 28 * density, bannerPaint)

        val r = 11 * density
        for (m in markers) {
            canvas.drawCircle(m.x, m.y, r, markerPaint)
            canvas.drawText(m.number.toString(), m.x, m.y + markerTextPaint.textSize / 3, markerTextPaint)
        }
        for (s in active.values) canvas.drawCircle(s.viewX, s.viewY, 24 * density, fingerPaint)
    }

    private companion object {
        const val MIN_MOVE_PX = 4f
        const val MAX_MARKERS = 20
    }
}
