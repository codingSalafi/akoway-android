package com.akoway.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Akoway keyboard: a voice-only panel for when the edge tab can't reach a text box. Switch to
 * it from your usual keyboard (long-press Gboard's space bar); it starts listening straight away,
 * writes what you said, and switches back to your usual keyboard.
 */
class AkowayKeyboard : InputMethodService() {

    private enum class State { IDLE, LISTENING, PROCESSING, ERROR }

    private val main = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private var generation = 0   // bumped on cancel so a late result is dropped
    private var state = State.IDLE

    private lateinit var pill: PillView
    private lateinit var label: TextView

    override fun onCreate() {
        super.onCreate()
        Speech.acquire(this)
    }

    override fun onDestroy() {
        cancel()
        Speech.release()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode() = false   // never cover the app in landscape

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateInputView(): View {
        pill = PillView(this).apply {
            widthDp = 210f
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_UP) when {
                    isOnClose(e.x, e.y) -> if (state == State.LISTENING || state == State.PROCESSING) cancel() else backToKeyboard()
                    isOnCircle(e.x, e.y) -> onMicTapped()
                }
                true
            }
        }
        label = TextView(this).apply {
            setTextColor(0xFFF6F6F6.toInt())
            textSize = 17f
            typeface = resources.getFont(R.font.instrument_serif)
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }

        val switchKey = key("⌨", "Back to your keyboard") { backToKeyboard() }.apply {
            setOnLongClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker(); true }
        }
        val deleteKey = key("⌫", "Delete") { deleteOne() }.apply { setOnTouchListener(RepeatDelete()) }
        val enterKey = key("⏎", "Enter") { enter() }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(14))
            addView(switchKey, LinearLayout.LayoutParams(dp(64), dp(64)))
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(pill)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(deleteKey, LinearLayout.LayoutParams(dp(64), dp(56)).apply { bottomMargin = dp(8) })
                addView(enterKey, LinearLayout.LayoutParams(dp(64), dp(56)))
            })
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111111.toInt())
            addView(label)
            addView(row)
        }
        // Keep clear of the navigation bar, which the keyboard window extends behind
        panel.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            insets
        }
        return panel
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        setState(State.IDLE)
        // Opening the keyboard means you want to speak, so start listening at once
        if (!restarting) startListening()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        cancel()
        super.onFinishInputView(finishingInput)
    }

    // ── Dictation ─────────────────────────────────────────────────────────────

    private fun onMicTapped() {
        when (state) {
            State.IDLE, State.ERROR -> startListening()
            State.LISTENING -> stopListening()
            State.PROCESSING -> {}
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
        Speech.warmUp(this)
    }

    private fun stopListening() {
        val audio = recorder.stop()
        pill.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        if (audio.size < Recorder.SAMPLE_RATE * 3 / 10) return setState(State.IDLE)  // under 0.3 s: a mis-tap
        if (audio.all { it == 0f }) return showError("Android blocked the microphone, so nothing was recorded.")

        setState(State.PROCESSING)
        val mine = ++generation
        Speech.transcribe(this, audio) { text ->
            if (mine != generation) return@transcribe   // cancelled meanwhile
            when {
                text == null -> showError("Something went wrong turning that into text. Try again.")
                text.isBlank() -> { setState(State.IDLE); label.text = "Didn't catch any words. Tap the mic to try again." }
                else -> write(text)
            }
        }
    }

    private fun write(text: String) {
        val ic = currentInputConnection ?: return setState(State.IDLE)
        val before = ic.getTextBeforeCursor(1, 0)
        val piece = if (!before.isNullOrEmpty() && !before.last().isWhitespace()) " $text" else text
        ic.commitText(piece, 1)
        Log.i(TAG, "Committed ${piece.length} chars")
        setState(State.IDLE)
        label.text = "Done."
        main.postDelayed({ if (state == State.IDLE) backToKeyboard(offerList = false) }, 400)
    }

    private fun cancel() {
        generation++
        if (state == State.LISTENING) recorder.stop()
        if (::pill.isInitialized) setState(State.IDLE)
    }

    private fun setState(s: State) {
        state = s
        pill.look = when (s) {
            State.IDLE -> PillView.Look.IDLE
            State.LISTENING -> PillView.Look.LISTENING
            State.PROCESSING -> PillView.Look.PROCESSING
            State.ERROR -> PillView.Look.ERROR
        }
        label.text = when (s) {
            State.IDLE -> "Tap the mic to speak."
            State.LISTENING -> "Listening… tap the mic when you're done."
            State.PROCESSING -> "Writing it down…"
            State.ERROR -> label.text
        }
    }

    private fun showError(message: String) {
        setState(State.ERROR)
        label.text = message
    }

    // ── Keys ──────────────────────────────────────────────────────────────────

    private fun backToKeyboard(offerList: Boolean = true) {
        cancel()
        val switched = if (Build.VERSION.SDK_INT >= 28) switchToPreviousInputMethod()
        else @Suppress("DEPRECATION") (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .switchToLastInputMethod(window.window?.attributes?.token)
        // Akoway is the only keyboard turned on: offer the list instead
        if (!switched && offerList) (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    private fun deleteOne() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        val noAction = (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            currentInputConnection?.performEditorAction(action)
        else
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun key(glyph: String, description: String, onClick: () -> Unit) = TextView(this).apply {
        text = glyph
        textSize = 24f
        gravity = Gravity.CENTER
        setTextColor(0xFFF6F6F6.toInt())
        contentDescription = description
        background = GradientDrawable().apply { setColor(0xFF2E2E2E.toInt()); cornerRadius = dp(14).toFloat() }
        isHapticFeedbackEnabled = true
        setOnClickListener { it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); onClick() }
    }

    /** Hold backspace to keep deleting, like a normal keyboard. */
    private inner class RepeatDelete : View.OnTouchListener {
        private val repeat = object : Runnable {
            override fun run() { deleteOne(); main.postDelayed(this, 60) }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    deleteOne()
                    main.postDelayed(repeat, 450)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.isPressed = false; main.removeCallbacks(repeat) }
            }
            return true
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Akoway"
    }
}
