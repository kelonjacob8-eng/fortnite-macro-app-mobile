package com.tapmacro.recorder

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: MacroStore
    private lateinit var serviceStatus: TextView
    private lateinit var restrictedHint: TextView
    private lateinit var btnAppInfo: Button
    private lateinit var macroList: LinearLayout
    private lateinit var macrosEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = MacroStore(this)
        applySystemBarPadding()

        serviceStatus = findViewById(R.id.serviceStatus)
        restrictedHint = findViewById(R.id.restrictedHint)
        btnAppInfo = findViewById(R.id.btnAppInfo)
        macroList = findViewById(R.id.macroList)
        macrosEmpty = findViewById(R.id.macrosEmpty)

        findViewById<Button>(R.id.btnAccessibility).setOnClickListener { openAccessibilitySettings() }
        btnAppInfo.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        }
        findViewById<Button>(R.id.btnShowControls).setOnClickListener { showControls() }

        bindSettings()
    }

    override fun onResume() {
        super.onResume()
        MacroService.onStateChanged = { refresh() }
        refresh()
    }

    override fun onPause() {
        MacroService.onStateChanged = null
        super.onPause()
    }

    private fun refresh() {
        val on = MacroService.instance != null || isServiceEnabledInSettings()
        serviceStatus.text = getString(if (on) R.string.status_on else R.string.status_off)
        serviceStatus.setTextColor(getColor(if (on) R.color.ok else R.color.record))
        // The "restricted settings" lock only exists on Android 13+ for sideloaded apps.
        val showRestricted = !on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        restrictedHint.visibility = if (showRestricted) View.VISIBLE else View.GONE
        btnAppInfo.visibility = if (showRestricted) View.VISIBLE else View.GONE
        renderMacros()
    }

    // ------------------------------------------------------------ actions

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            // Asks Settings to highlight our entry; ignored where unsupported.
            val key = ComponentName(this@MainActivity, MacroService::class.java).flattenToString()
            putExtra(":settings:fragment_args_key", key)
            putExtra(":settings:show_fragment_args", Bundle().apply { putString(":settings:fragment_args_key", key) })
        }
        startActivity(intent)
    }

    private fun showControls() {
        val service = MacroService.instance
        if (service == null) {
            Toast.makeText(this, R.string.enable_first, Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        store.panelVisible = true
        service.showPanel()
        Toast.makeText(this, R.string.controls_shown, Toast.LENGTH_LONG).show()
    }

    private fun isServiceEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, MacroService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ----------------------------------------------------------- settings

    private fun bindSettings() {
        val repeat = findViewById<EditText>(R.id.inputRepeat)
        val delay = findViewById<EditText>(R.id.inputDelay)
        val speed = findViewById<EditText>(R.id.inputSpeed)
        val passThrough = findViewById<Switch>(R.id.switchPassThrough)

        repeat.setText(store.repeatCount.toString())
        delay.setText(formatNumber(store.repeatDelayMs / 1000.0))
        speed.setText(formatNumber(store.speed.toDouble()))
        passThrough.isChecked = store.passThrough

        repeat.onChange { text -> text.toIntOrNull()?.let { store.repeatCount = it } }
        delay.onChange { text -> text.toDoubleOrNull()?.let { store.repeatDelayMs = (it * 1000).toLong() } }
        speed.onChange { text ->
            text.toFloatOrNull()?.takeIf { it > 0f }?.let { store.speed = it }
        }
        // Show the value that was actually stored (e.g. after clamping) when leaving a field.
        speed.setOnFocusChangeListener { _, focused ->
            if (!focused) speed.setText(formatNumber(store.speed.toDouble()))
        }
        delay.setOnFocusChangeListener { _, focused ->
            if (!focused) delay.setText(formatNumber(store.repeatDelayMs / 1000.0))
        }
        repeat.setOnFocusChangeListener { _, focused ->
            if (!focused) repeat.setText(store.repeatCount.toString())
        }
        passThrough.setOnCheckedChangeListener { _, checked -> store.passThrough = checked }
    }

    private fun EditText.onChange(block: (String) -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = block(s?.toString()?.trim().orEmpty())
        })
    }

    // ------------------------------------------------------------- macros

    private fun renderMacros() {
        val macros = store.list()
        val selectedId = store.selected?.id
        macroList.removeAllViews()
        macrosEmpty.visibility = if (macros.isEmpty()) View.VISIBLE else View.GONE

        val density = resources.displayMetrics.density
        for (macro in macros) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.row_bg)
                setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                setOnClickListener {
                    store.selectedId = macro.id
                    renderMacros()
                }
            }

            val radio = RadioButton(this).apply {
                isChecked = macro.id == selectedId
                isClickable = false
                isFocusable = false
            }
            row.addView(radio)

            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = macro.name
                textSize = 16f
                setTextColor(getColor(R.color.text))
            })
            texts.addView(TextView(this).apply {
                text = resources.getQuantityString(
                    R.plurals.macro_details,
                    macro.tapCount,
                    macro.tapCount,
                    formatDuration(macro.durationMs),
                    getString(if (macro.isLandscape) R.string.landscape else R.string.portrait),
                )
                textSize = 13f
                setTextColor(getColor(R.color.text_dim))
            })
            row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            row.addView(iconButton(R.drawable.ic_edit, R.string.rename) { promptRename(macro) })
            row.addView(iconButton(R.drawable.ic_delete, R.string.delete) { confirmDelete(macro) })

            macroList.addView(row)
        }
    }

    private fun iconButton(icon: Int, label: Int, onClick: () -> Unit): ImageButton {
        val size = (44 * resources.displayMetrics.density).toInt()
        return ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundResource(R.drawable.round_ripple)
            contentDescription = getString(label)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    private fun promptRename(macro: Macro) {
        val input = EditText(this).apply {
            setText(macro.name)
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            maxLines = 1
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                store.rename(macro.id, input.text.toString().take(40))
                renderMacros()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        input.requestFocus()
    }

    private fun confirmDelete(macro: Macro) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.delete_confirm, macro.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(macro.id)
                renderMacros()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // -------------------------------------------------------------- misc

    /** Keeps content clear of the status/navigation bars (Android 15 draws edge to edge). */
    private fun applySystemBarPadding() {
        val root = findViewById<View>(R.id.root)
        val base = root.paddingLeft
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(base + bars.left, base + bars.top, base + bars.right, base + maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    base + insets.systemWindowInsetLeft,
                    base + insets.systemWindowInsetTop,
                    base + insets.systemWindowInsetRight,
                    base + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSecs = (ms + 500) / 1000
        return "%d:%02d".format(Locale.US, totalSecs / 60, totalSecs % 60)
    }

    private fun formatNumber(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.US, v).trimEnd('0').trimEnd('.')
}
