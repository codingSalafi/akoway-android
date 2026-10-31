package com.akoway.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Akoway's Android side: a small tab on the right edge of the screen, over every app. Tap it and
 * the pill slides out; tap the mic to start, tap again to stop, and the words go into whatever
 * text box is focused. Runs as an accessibility service, which is what lets it draw over other
 * apps and type into them.
 */
class AkowayService : AccessibilityService() {

    private enum class State { IDLE, LISTENING, PROCESSING, WRITING, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val recorder = Recorder()
    private var engine: BarkEngine? = null   // only touched on the worker thread
    private var generation = 0               // bumped on cancel so a late result is dropped
    private var state = State.IDLE

    private lateinit var windows: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: LinearLayout
    private lateinit var handle: View
    private lateinit var pill: PillView
    private lateinit var label: TextView

    override fun onServiceConnected() {
        windows = getSystemService(WINDOW_SERVICE) as WindowManager
        buildOverlay()
        if (SpeechModel.isReady(this)) worker.execute { loadEngine() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        generation++
        if (state == State.LISTENING) recorder.stop()
        if (::root.isInitialized) windows.removeView(root)
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model holds about 1.3 GB; give it back if Android is running out, it reloads in ~3 s
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL && state == State.IDLE) worker.execute { engine?.close(); engine = null }
    }

    // ── Dictation ─────────────────────────────────────────────────────────────

    private fun onMicTapped() {
        when (state) {
            State.IDLE, State.ERROR -> startListening()
            State.LISTENING -> stopListening()
            else -> {}
        }
    }

    private fun startListening() {
        if (!SpeechModel.isReady(this)) return showError("Open the Akoway app to download the speech model.")
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return showError("Open the Akoway app and allow the microphone.")
        try {
            recorder.start()
        } catch (e: Exception) {
            return showError("Couldn't start the microphone. Is another app using it?")
        }
        pill.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        setState(State.LISTENING)
        worker.execute { loadEngine() }   // usually already loaded; this covers a cold start
    }

    private fun stopListening() {
        val audio = recorder.stop()
        pill.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (audio.size < Recorder.SAMPLE_RATE * 3 / 10) return setState(State.IDLE)  // under 0.3 s: a mis-tap
        if (audio.all { it == 0f }) return showError("Android blocked the microphone, so nothing was recorded.")

        setState(State.PROCESSING)
        val mine = ++generation
        worker.execute {
            val text = try {
                loadEngine()!!.transcribe(audio)
            } catch (e: Throwable) {
                Log.e(TAG, "Transcription failed", e)
                null
            }
            main.post {
                if (mine != generation) return@post   // cancelled meanwhile
                when {
                    text == null -> showError("Something went wrong turning that into text. Try again.")
                    text.isBlank() -> { Log.i(TAG, "No words"); setState(State.IDLE); showLabel("Didn't catch any words.") }
                    else -> write(text)
                }
            }
        }
    }

    private fun write(text: String) {
        setState(State.WRITING)
        val result = TextInserter.insert(this, text)
        Log.i(TAG, "${result.name}: ${text.length} chars")
        if (result == TextInserter.Result.COPIED_ONLY) {
            // Stay open while the note is up, then tuck away
            showLabel("No text box selected, so it's copied. Paste it where you want.")
            main.postDelayed({ if (state == State.WRITING) setState(State.IDLE) }, 900)
        } else {
            main.postDelayed({ if (state == State.WRITING) { setState(State.IDLE); collapse() } }, 900)
        }
    }

    /** Close button: stop without writing anything. */
    private fun cancel() {
        generation++
        if (state == State.LISTENING) recorder.stop()
        setState(State.IDLE)
        collapse()
    }

    private fun loadEngine(): BarkEngine? {
        if (engine == null && SpeechModel.isReady(this)) {
            val start = System.nanoTime()
            engine = BarkEngine(SpeechModel.folder(this)).also { it.transcribe(FloatArray(8000)) }  // warm-up
            Log.i(TAG, "Model loaded in ${(System.nanoTime() - start) / 1_000_000} ms")
        }
        return engine
    }

    private fun setState(s: State) {
        state = s
        pill.look = when (s) {
            State.IDLE -> PillView.Look.IDLE
            State.LISTENING -> PillView.Look.LISTENING
            State.PROCESSING -> PillView.Look.PROCESSING
            State.WRITING -> PillView.Look.WRITING
            State.ERROR -> PillView.Look.ERROR
        }
        if (s == State.LISTENING || s == State.PROCESSING) label.visibility = View.GONE
        restartAutoCollapse()
    }

    private fun showError(message: String) {
        setState(State.ERROR)
        showLabel(message)
        main.postDelayed({ if (state == State.ERROR) setState(State.IDLE) }, 6_000)
    }

    private fun showLabel(message: String) {
        label.text = message
        label.visibility = View.VISIBLE
        main.removeCallbacks(hideLabel)
        main.postDelayed(hideLabel, 6_000)
    }

    private val hideLabel = Runnable { label.visibility = View.GONE }
    private val autoCollapse = Runnable { if (state == State.IDLE && label.visibility != View.VISIBLE) collapse() else restartAutoCollapse() }

    private fun restartAutoCollapse() {
        main.removeCallbacks(autoCollapse)
        if (state == State.IDLE) main.postDelayed(autoCollapse, 10_000)
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    private fun dp(v: Float) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun buildOverlay() {
        handle = HandleView(this)
        pill = PillView(this)
        label = TextView(this).apply {
            setTextColor(0xFFF6F6F6.toInt())
            textSize = 16f
            typeface = resources.getFont(R.font.instrument_serif)
            setPadding(dp(12f), dp(5f), dp(12f), dp(6f))
            maxWidth = dp(PillView.WIDTH_DP + 40)
            background = GradientDrawable().apply { setColor(0xFF111111.toInt()); cornerRadius = dp(10f).toFloat() }
            visibility = View.GONE
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(handle)
            addView(pill.apply { visibility = View.GONE })
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6f); rightMargin = dp(6f)
            })
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // Never take focus, so the app's text box (and keyboard) stay active underneath
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            y = prefs().getInt(KEY_Y, resources.displayMetrics.heightPixels * 2 / 5)
        }
        windows.addView(root, params)

        handle.setOnTouchListener(DragOrTap { _, _ -> expand() })
        pill.setOnTouchListener(DragOrTap { x, y ->
            when {
                pill.isOnClose(x, y) -> cancel()
                pill.isOnCircle(x, y) -> onMicTapped()
            }
        })
    }

    private fun expand() {
        handle.visibility = View.GONE
        pill.visibility = View.VISIBLE
        pill.translationX = dp(PillView.WIDTH_DP).toFloat()
        pill.animate().translationX(0f).setDuration(160).start()
        setState(state)   // restarts the auto-collapse timer
    }

    private fun collapse() {
        if (state == State.LISTENING || state == State.PROCESSING) return
        label.visibility = View.GONE
        pill.visibility = View.GONE
        handle.visibility = View.VISIBLE
    }

    private fun prefs() = getSharedPreferences("overlay", Context.MODE_PRIVATE)

    /** Drag up and down to move the overlay; a touch that doesn't move is a tap at (x, y). */
    private inner class DragOrTap(private val onTap: (Float, Float) -> Unit) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@AkowayService).scaledTouchSlop
        private var downY = 0f
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = params.y; dragging = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && abs(e.rawY - downY) > slop) dragging = true
                    if (dragging) {
                        params.y = (startY + (e.rawY - downY)).toInt().coerceIn(0, resources.displayMetrics.heightPixels - v.height)
                        windows.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) prefs().edit().putInt(KEY_Y, params.y).apply()
                    else onTap(e.x, e.y)
                    restartAutoCollapse()
                }
            }
            return true
        }
    }

    /** The collapsed tab: a lime strip hugging the right edge, with a wider invisible touch area. */
    private inner class HandleView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        init { contentDescription = "Akoway: tap to dictate" }

        override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(dp(28f), dp(72f))

        override fun onDraw(canvas: Canvas) {
            val r = dp(8f).toFloat()
            paint.color = PillView.LIME
            rect.set(width - dp(14f).toFloat(), 0f, width + r, height.toFloat())
            canvas.drawRoundRect(rect, r, r, paint)
            paint.color = 0xFF028700.toInt()   // grip line
            rect.set(width - dp(9f).toFloat(), height * 0.3f, width - dp(6f).toFloat(), height * 0.7f)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }

    companion object {
        private const val TAG = "Akoway"
        private const val KEY_Y = "y"
    }
}
