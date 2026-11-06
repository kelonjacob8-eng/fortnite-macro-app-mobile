package com.tapmacro.recorder

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

/**
 * Runs while Tap Macro is switched on in Accessibility settings. It owns the
 * floating control bar, the recording layer and the player.
 *
 * Windows are added as TYPE_ACCESSIBILITY_OVERLAY, so no separate
 * "draw over other apps" permission is needed.
 */
class MacroService : AccessibilityService() {

    enum class Mode { IDLE, RECORDING, PLAYING }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private lateinit var store: MacroStore

    private var panel: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var capture: CaptureView? = null
    private var captureParams: WindowManager.LayoutParams? = null

    var mode = Mode.IDLE
        private set

    // Recording state
    private var recordStart = 0L
    private var recordScreen = ScreenInfo(0, 0, 0)
    private val recorded = ArrayList<Stroke>()
    private var forwarding = false

    // Playback state
    private var player: MacroPlayer? = null
    private var pass = 0
    private var totalPasses = 0

    private val ticker = object : Runnable {
        override fun run() {
            updatePanel()
            if (mode != Mode.IDLE) handler.postDelayed(this, 250)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        store = MacroStore(this)
        instance = this
        if (store.panelVisible) showPanel()
        notifyStateChanged()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        stopPlayback()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance !== this) return
        player?.stop()
        player = null
        removeCapture()
        removePanel()
        mode = Mode.IDLE
        handler.removeCallbacksAndMessages(null)
        instance = null
        notifyStateChanged()
    }

    // ---------------------------------------------------------------- panel

    val isPanelShowing: Boolean get() = panel != null

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun showPanel() {
        if (panel != null) return
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_panel, null)

        val density = resources.displayMetrics.density
        val params = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = store.panelX
            y = store.panelY.takeIf { it >= 0 } ?: (120 * density).toInt()
        }

        view.findViewById<ImageButton>(R.id.btnRecord).setOnClickListener {
            if (mode == Mode.RECORDING) stopRecording() else startRecording()
        }
        view.findViewById<ImageButton>(R.id.btnPlay).setOnClickListener {
            if (mode == Mode.PLAYING) stopPlayback() else startPlayback()
        }
        view.findViewById<ImageButton>(R.id.btnClose).setOnClickListener {
            store.panelVisible = false
            hidePanel()
        }
        view.findViewById<View>(R.id.handle).setOnTouchListener(DragListener(view, params))

        windowManager.addView(view, params)
        panel = view
        panelParams = params
        updatePanel()
        notifyStateChanged()
    }

    fun hidePanel() {
        if (mode == Mode.RECORDING) stopRecording()
        if (mode == Mode.PLAYING) stopPlayback()
        removePanel()
        notifyStateChanged()
    }

    private fun removePanel() {
        panel?.let { runCatching { windowManager.removeViewImmediate(it) } }
        panel = null
        panelParams = null
    }

    /** Puts the panel back on top of the recording layer so its buttons stay reachable. */
    private fun raisePanel() {
        val view = panel ?: return
        val params = panelParams ?: return
        runCatching {
            windowManager.removeViewImmediate(view)
            windowManager.addView(view, params)
        }
    }

    private fun setKeepScreenOn(on: Boolean) {
        val view = panel ?: return
        val params = panelParams ?: return
        params.flags = if (on) {
            params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun updatePanel() {
        val view = panel ?: return
        val record = view.findViewById<ImageButton>(R.id.btnRecord)
        val play = view.findViewById<ImageButton>(R.id.btnPlay)
        val status = view.findViewById<TextView>(R.id.status)

        record.setImageResource(if (mode == Mode.RECORDING) R.drawable.ic_stop else R.drawable.ic_record)
        record.contentDescription = getString(if (mode == Mode.RECORDING) R.string.cd_stop_record else R.string.cd_record)
        record.isEnabled = mode != Mode.PLAYING
        record.alpha = if (record.isEnabled) 1f else 0.3f

        play.setImageResource(if (mode == Mode.PLAYING) R.drawable.ic_stop else R.drawable.ic_play)
        play.contentDescription = getString(if (mode == Mode.PLAYING) R.string.cd_stop_play else R.string.cd_play)
        play.isEnabled = mode != Mode.RECORDING
        play.alpha = if (play.isEnabled) 1f else 0.3f

        val text = when (mode) {
            Mode.IDLE -> ""
            Mode.RECORDING -> {
                val secs = (SystemClock.uptimeMillis() - recordStart) / 1000
                "REC\n${secs / 60}:${"%02d".format(secs % 60)}\n${recorded.size}"
            }
            Mode.PLAYING -> {
                val of = if (totalPasses == 0) "∞" else totalPasses.toString()
                "▶\n$pass/$of"
            }
        }
        status.text = text
        status.setTextColor(if (mode == Mode.RECORDING) getColor(R.color.record) else 0xFFFFFFFF.toInt())
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------ recording

    private fun startRecording() {
        if (mode != Mode.IDLE) return
        mode = Mode.RECORDING
        recorded.clear()
        forwarding = false
        recordStart = SystemClock.uptimeMillis()
        recordScreen = ScreenInfo.get(this)

        val view = CaptureView(this) { strokes -> onGestureRecorded(strokes) }
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            extraFlags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        windowManager.addView(view, params)
        capture = view
        captureParams = params
        raisePanel()

        handler.post(ticker)
        notifyStateChanged()
    }

    private fun onGestureRecorded(strokes: List<Stroke>) {
        if (mode != Mode.RECORDING || forwarding) return
        val relative = strokes.map { it.copy(startMs = (it.startMs - recordStart).coerceAtLeast(0L)) }
        recorded += relative
        updatePanel()
        if (store.passThrough) forwardToApp(relative)
    }

    /**
     * Sends what was just recorded on to the game underneath. The recording
     * layer is made untouchable for a moment so the tap goes through it.
     * Touches made during that short window reach the game but aren't recorded.
     */
    private fun forwardToApp(strokes: List<Stroke>) {
        val view = capture ?: return
        val params = captureParams ?: return
        val first = strokes.minOf { it.startMs }
        val groups = MacroPlanner.plan(strokes.map { it.copy(startMs = it.startMs - first) })
        if (groups.isEmpty()) return

        forwarding = true
        view.forwarding = true
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, params) }

        // Give the window manager a moment to apply the change before tapping.
        handler.postDelayed({
            GestureSender.sendAll(this, groups, handler) {
                // If recording was stopped (or restarted) meanwhile, that state is already reset.
                if (capture === view && mode == Mode.RECORDING) {
                    forwarding = false
                    view.forwarding = false
                    params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }
        }, FORWARD_DELAY_MS)
    }

    private fun stopRecording() {
        if (mode != Mode.RECORDING) return
        val duration = SystemClock.uptimeMillis() - recordStart
        mode = Mode.IDLE
        removeCapture()
        handler.removeCallbacks(ticker)

        if (recorded.isEmpty()) {
            toast(getString(R.string.nothing_recorded))
        } else {
            val macro = Macro(
                id = UUID.randomUUID().toString(),
                name = store.nextDefaultName(),
                createdAt = System.currentTimeMillis(),
                durationMs = maxOf(duration, recorded.maxOf { it.endMs }),
                screenWidth = recordScreen.width,
                screenHeight = recordScreen.height,
                rotation = recordScreen.rotation,
                strokes = recorded.toList(),
            )
            store.save(macro)
            store.selectedId = macro.id
            toast(resources.getQuantityString(R.plurals.saved_macro, macro.tapCount, macro.name, macro.tapCount))
        }
        recorded.clear()
        updatePanel()
        notifyStateChanged()
    }

    private fun removeCapture() {
        capture?.let { runCatching { windowManager.removeViewImmediate(it) } }
        capture = null
        captureParams = null
        forwarding = false
    }

    // ------------------------------------------------------------- playback

    private fun startPlayback() {
        if (mode != Mode.IDLE) return
        val macro = store.selected
        if (macro == null) {
            toast(getString(R.string.no_macro))
            return
        }
        val screen = ScreenInfo.get(this)
        if (macro.screenWidth > 0 && macro.isLandscape != (screen.width > screen.height)) {
            val recordedIn = getString(if (macro.isLandscape) R.string.landscape else R.string.portrait)
            toast(getString(R.string.orientation_warning, recordedIn))
        }

        mode = Mode.PLAYING
        totalPasses = store.repeatCount
        pass = 0
        setKeepScreenOn(true)
        val p = MacroPlayer(
            service = this,
            handler = handler,
            onPassStarted = { pass = it; updatePanel() },
            onFinished = {
                if (mode == Mode.PLAYING) {
                    toast(getString(R.string.playback_done))
                    stopPlayback()
                }
            },
        )
        player = p
        handler.post(ticker)
        notifyStateChanged()
        p.start(macro, store.repeatCount, store.repeatDelayMs, store.speed)
    }

    fun stopPlayback() {
        player?.stop()
        player = null
        if (mode == Mode.PLAYING) mode = Mode.IDLE
        setKeepScreenOn(false)
        handler.removeCallbacks(ticker)
        updatePanel()
        notifyStateChanged()
    }

    // ---------------------------------------------------------------- utils

    private fun overlayParams(width: Int, height: Int, extraFlags: Int = 0) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                extraFlags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    /** Moves the panel when its handle is dragged, keeping it on screen. */
    private inner class DragListener(
        private val view: View,
        private val params: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var screen = ScreenInfo(0, 0, 0)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    screen = ScreenInfo.get(this@MacroService)
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX + (e.rawX - downX)).toInt()
                        .coerceIn(0, maxOf(0, screen.width - view.width))
                    params.y = (startY + (e.rawY - downY)).toInt()
                        .coerceIn(0, maxOf(0, screen.height - view.height))
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    store.panelX = params.x
                    store.panelY = params.y
                }
            }
            return true
        }
    }

    companion object {
        private const val FORWARD_DELAY_MS = 60L

        /**
         * The running service, or null when it's switched off. Cleared in
         * [shutdown], so it never outlives the service. Main-thread only.
         */
        @SuppressLint("StaticFieldLeak")
        var instance: MacroService? = null
            private set

        /** Lets the app screen refresh when something changes here. Main-thread only. */
        var onStateChanged: (() -> Unit)? = null

        private fun notifyStateChanged() {
            onStateChanged?.invoke()
        }
    }
}
