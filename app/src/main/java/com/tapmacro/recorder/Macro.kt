package com.tapmacro.recorder

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

/** One sampled finger position. [t] is milliseconds since the finger went down. */
data class TouchPoint(val t: Long, val x: Float, val y: Float)

/**
 * One finger touching the screen, from down to up. A tap is a stroke whose
 * points all sit in the same place; a swipe or drag has points that move.
 *
 * [startMs] is milliseconds since recording started.
 */
data class Stroke(val startMs: Long, val points: List<TouchPoint>) {
    val durationMs: Long get() = points.lastOrNull()?.t ?: 0L
    val endMs: Long get() = startMs + durationMs
}

data class Macro(
    val id: String,
    val name: String,
    val createdAt: Long,
    /** Length of the whole recording, including any wait before the first or after the last tap. */
    val durationMs: Long,
    /** Screen size and rotation at recording time, so playback can adapt or warn. */
    val screenWidth: Int,
    val screenHeight: Int,
    val rotation: Int,
    val strokes: List<Stroke>,
) {
    val tapCount: Int get() = strokes.size
    val isLandscape: Boolean get() = screenWidth > screenHeight
}

object MacroJson {
    private const val VERSION = 1

    fun toJson(macro: Macro): String {
        val strokes = JSONArray()
        for (stroke in macro.strokes) {
            // Points are stored flat as [t, x, y, t, x, y, ...] to keep files small.
            val pts = JSONArray()
            for (p in stroke.points) {
                pts.put(p.t)
                pts.put(round1(p.x))
                pts.put(round1(p.y))
            }
            strokes.put(JSONObject().put("start", stroke.startMs).put("points", pts))
        }
        return JSONObject()
            .put("version", VERSION)
            .put("id", macro.id)
            .put("name", macro.name)
            .put("createdAt", macro.createdAt)
            .put("durationMs", macro.durationMs)
            .put("screenWidth", macro.screenWidth)
            .put("screenHeight", macro.screenHeight)
            .put("rotation", macro.rotation)
            .put("strokes", strokes)
            .toString()
    }

    fun fromJson(text: String): Macro {
        val o = JSONObject(text)
        val strokesJson = o.getJSONArray("strokes")
        val strokes = ArrayList<Stroke>(strokesJson.length())
        for (i in 0 until strokesJson.length()) {
            val s = strokesJson.getJSONObject(i)
            val pts = s.getJSONArray("points")
            val points = ArrayList<TouchPoint>(pts.length() / 3)
            var j = 0
            while (j + 2 < pts.length()) {
                points += TouchPoint(
                    t = pts.getLong(j),
                    x = pts.getDouble(j + 1).toFloat(),
                    y = pts.getDouble(j + 2).toFloat(),
                )
                j += 3
            }
            if (points.isNotEmpty()) strokes += Stroke(s.getLong("start"), points)
        }
        return Macro(
            id = o.getString("id"),
            name = o.optString("name", "Macro"),
            createdAt = o.optLong("createdAt", 0L),
            durationMs = o.optLong("durationMs", strokes.maxOfOrNull { it.endMs } ?: 0L),
            screenWidth = o.optInt("screenWidth", 0),
            screenHeight = o.optInt("screenHeight", 0),
            rotation = o.optInt("rotation", 0),
            strokes = strokes,
        )
    }

    private fun round1(v: Float): Double = (v * 10.0).roundToLong() / 10.0
}
