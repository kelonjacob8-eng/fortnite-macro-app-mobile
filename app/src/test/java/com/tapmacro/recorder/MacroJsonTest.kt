package com.tapmacro.recorder

import org.junit.Assert.assertEquals
import org.junit.Test

class MacroJsonTest {

    @Test
    fun roundTripKeepsEverything() {
        val macro = Macro(
            id = "1b4e28ba-2fa1-11d2-883f-0016d3cca427",
            name = "Farm \"loop\"",
            createdAt = 1_700_000_000_000,
            durationMs = 12_345,
            screenWidth = 2400,
            screenHeight = 1080,
            rotation = 1,
            strokes = listOf(
                Stroke(100, listOf(TouchPoint(0, 10.5f, 20.25f), TouchPoint(90, 10.5f, 20.25f))),
                Stroke(800, listOf(TouchPoint(0, 1f, 2f), TouchPoint(40, 50f, 60f), TouchPoint(300, 99f, 120f))),
            ),
        )
        val back = MacroJson.fromJson(MacroJson.toJson(macro))

        assertEquals(macro.copy(strokes = emptyList()), back.copy(strokes = emptyList()))
        assertEquals(2, back.strokes.size)
        assertEquals(800L, back.strokes[1].startMs)
        assertEquals(300L, back.strokes[1].durationMs)
        // Positions are stored to 0.1 px.
        assertEquals(20.3f, back.strokes[0].points[0].y, 0.001f)
        assertEquals(TouchPoint(40, 50f, 60f), back.strokes[1].points[1])
    }

    @Test
    fun missingOptionalFieldsGetDefaults() {
        val json = """{"id":"abc","strokes":[{"start":5,"points":[0,1,2,70,1,2]},{"start":9,"points":[]}]}"""
        val macro = MacroJson.fromJson(json)
        assertEquals("Macro", macro.name)
        assertEquals(1, macro.strokes.size) // empty stroke dropped
        assertEquals(75L, macro.durationMs)
    }
}
