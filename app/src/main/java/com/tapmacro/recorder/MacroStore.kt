package com.tapmacro.recorder

import android.content.Context
import android.util.Log
import java.io.File

/** Saves macros as small JSON files and keeps the playback settings. */
class MacroStore(context: Context) {
    private val dir = File(context.filesDir, "macros").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("tapmacro", Context.MODE_PRIVATE)

    fun list(): List<Macro> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { file ->
                try {
                    MacroJson.fromJson(file.readText())
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping unreadable macro ${file.name}", e)
                    null
                }
            }
            .sortedByDescending { it.createdAt }

    fun get(id: String): Macro? {
        val file = fileFor(id) ?: return null
        if (!file.exists()) return null
        return try {
            MacroJson.fromJson(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Could not read macro $id", e)
            null
        }
    }

    fun save(macro: Macro) {
        val file = fileFor(macro.id) ?: error("Bad macro id")
        val tmp = File(dir, "${macro.id}.tmp")
        tmp.writeText(MacroJson.toJson(macro))
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    fun rename(id: String, newName: String) {
        val macro = get(id) ?: return
        save(macro.copy(name = newName.trim().ifEmpty { macro.name }))
    }

    fun delete(id: String) {
        fileFor(id)?.delete()
        if (selectedId == id) selectedId = null
    }

    /** "Macro N" with the first N that isn't already taken. */
    fun nextDefaultName(): String {
        val taken = list().map { it.name }.toSet()
        var n = 1
        while ("Macro $n" in taken) n++
        return "Macro $n"
    }

    /** The macro that ▶ on the floating bar plays. Falls back to the newest one. */
    val selected: Macro?
        get() = selectedId?.let(::get) ?: list().firstOrNull()

    var selectedId: String?
        get() = prefs.getString(KEY_SELECTED, null)
        set(value) = prefs.edit().putString(KEY_SELECTED, value).apply()

    /** How many times to play the macro; 0 means forever. */
    var repeatCount: Int
        get() = prefs.getInt(KEY_REPEAT, 0)
        set(value) = prefs.edit().putInt(KEY_REPEAT, value.coerceIn(0, 999_999)).apply()

    var repeatDelayMs: Long
        get() = prefs.getLong(KEY_DELAY, 0L)
        set(value) = prefs.edit().putLong(KEY_DELAY, value.coerceIn(0L, 3_600_000L)).apply()

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value.coerceIn(MIN_SPEED, MAX_SPEED)).apply()

    var passThrough: Boolean
        get() = prefs.getBoolean(KEY_PASS_THROUGH, true)
        set(value) = prefs.edit().putBoolean(KEY_PASS_THROUGH, value).apply()

    var panelVisible: Boolean
        get() = prefs.getBoolean(KEY_PANEL_VISIBLE, true)
        set(value) = prefs.edit().putBoolean(KEY_PANEL_VISIBLE, value).apply()

    var panelX: Int
        get() = prefs.getInt(KEY_PANEL_X, 0)
        set(value) = prefs.edit().putInt(KEY_PANEL_X, value).apply()

    var panelY: Int
        get() = prefs.getInt(KEY_PANEL_Y, -1)
        set(value) = prefs.edit().putInt(KEY_PANEL_Y, value).apply()

    private fun fileFor(id: String): File? =
        if (ID_PATTERN.matches(id)) File(dir, "$id.json") else null

    companion object {
        private const val TAG = "MacroStore"
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        private val ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        private const val KEY_SELECTED = "selected"
        private const val KEY_REPEAT = "repeat"
        private const val KEY_DELAY = "delay_ms"
        private const val KEY_SPEED = "speed"
        private const val KEY_PASS_THROUGH = "pass_through"
        private const val KEY_PANEL_VISIBLE = "panel_visible"
        private const val KEY_PANEL_X = "panel_x"
        private const val KEY_PANEL_Y = "panel_y"
    }
}
