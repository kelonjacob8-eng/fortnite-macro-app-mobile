package com.tapmacro.recorder

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display

data class ScreenInfo(val width: Int, val height: Int, val rotation: Int) {
    companion object {
        /** Full size of the built-in display in its current rotation, in pixels. */
        fun get(context: Context): ScreenInfo {
            val display = context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
            val size = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(size)
            return ScreenInfo(size.x, size.y, display.rotation)
        }
    }
}
