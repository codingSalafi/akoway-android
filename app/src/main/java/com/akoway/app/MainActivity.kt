package com.akoway.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsetsController
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.akoway.app.Ui.dp
import com.akoway.app.Ui.dpi
import kotlin.concurrent.thread

/**
 * First run shows the welcome; after that, setup: download the model, allow the microphone, turn on
 * the Akoway keyboard. Each step turns lime with a tick once done,
 * and the screen rechecks whenever it comes back to the front.
 */
class MainActivity : Activity() {

    private inner class Step(private val iconKind: IconView.Icon, title: String, private val about: String, action: String, onClick: () -> Unit) {
        private val icon = IconView(this@MainActivity, iconKind, 30)
        private val iconBox = LinearLayout(this@MainActivity).apply {
            gravity = Gravity.CENTER
            addView(icon)
        }
        private val title = TextView(this@MainActivity).apply { text = title; textSize = 19f; typeface = Ui.heavy(); setTextColor(Ui.INK) }
        val detail = TextView(this@MainActivity).apply { text = about; textSize = 14.5f; setTextColor(Ui.SUBTLE); setLineSpacing(0f, 1.15f) }
        val button = Ui.button(this@MainActivity, action, Ui.INK, Ui.WHITE, onClick)
        val extra = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }   // e.g. the download bar
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(18), dpi(18), dpi(18), dpi(18))
            if (Build.VERSION.SDK_INT >= 28) {   // a soft, green-tinted shadow rather than a grey one
                outlineSpotShadowColor = 0x553A5A20
                outlineAmbientShadowColor = 0x333A5A20
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(iconBox, LinearLayout.LayoutParams(dpi(56), dpi(56)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpi(14), dpi(2), 0, 0)
                    addView(this@Step.title)
                    addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
                }, LinearLayout.LayoutParams(0, -2, 1f))
            })
            addView(extra, LinearLayout.LayoutParams(-1, -2))
            addView(button, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16) })
        }

        fun done(isDone: Boolean, doneText: String) {
            // Soft white cards throughout; the lime tick in the ink box is what says "done",
            // and the step still to do keeps an ink outline so it stands out
            view.background = if (isDone) Ui.rounded(Ui.WHITE, dp(28f)) else Ui.rounded(Ui.WHITE, dp(28f), dpi(2), Ui.INK)
            view.elevation = if (isDone) dp(3f) else dp(8f)
            iconBox.background = Ui.rounded(if (isDone) Ui.INK else Ui.PALE, dp(18f))
            icon.icon = if (isDone) IconView.Icon.CHECK else iconKind
            icon.invalidate()
            detail.text = if (isDone) doneText else about
            detail.setTextColor(Ui.SUBTLE)
            button.visibility = if (isDone) View.GONE else View.VISIBLE
        }
    }

    private lateinit var column: LinearLayout
    private lateinit var model: Step
    private lateinit var mic: Step
    private lateinit var keyboard: Step
    private lateinit var bar: BarView
    private lateinit var modelBar: BarView
    private lateinit var progressText: TextView
    private lateinit var ready: LinearLayout
    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (prefs().getBoolean(KEY_WELCOMED, false)) showSetup() else showWelcome()
    }

    override fun onResume() {
        super.onResume()
        if (::column.isInitialized) refresh(animate = false)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh(animate = true)

    // ── Welcome ───────────────────────────────────────────────────────────────

    private fun showWelcome() {
        lightBars(false)
        val welcome = WelcomeView(this) {
            prefs().edit().putBoolean(KEY_WELCOMED, true).apply()
            showSetup()
        }
        val side = dpi(24)
        welcome.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(side, insets.systemWindowInsetTop, side, insets.systemWindowInsetBottom + dpi(16))
            insets
        }
        setContentView(welcome)
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    private fun showSetup() {
        lightBars(true)
        model = Step(IconView.Icon.DOWNLOAD, "Download the voice model",
            "Bark 3.0 Clean, ${SpeechModel.downloadBytes / 1_000_000} MB, once. It tidies out \"um\"s and repeats as you talk.",
            "Download") { startDownload() }
        modelBar = BarView(this, Ui.PAPER, Ui.INK).apply { visibility = View.GONE }
        model.extra.addView(modelBar, LinearLayout.LayoutParams(-1, dpi(12)).apply { topMargin = dpi(16) })
        mic = Step(IconView.Icon.MIC, "Allow the microphone",
            "Your voice is turned into text right here on the phone.", "Allow") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        keyboard = Step(IconView.Icon.KEYBOARD, "Turn on the Akoway keyboard",
            "Switch on \"Akoway voice\". Android warns that keyboards can see what you type; Akoway never sends anything anywhere.",
            "Open settings") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }

        val steps = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(16), dpi(18), dpi(16), 0)
            clipToPadding = false
            clipChildren = false
            ready = readyCard()
            addView(ready, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(12) })
            for (step in listOf(model, mic, keyboard)) addView(step.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dpi(14) })
            addView(TextView(context).apply {
                text = "Bark 3.0 Clean by Underdog · runs fully on your phone"
                textSize = 13f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER
                setPadding(0, dpi(22), 0, dpi(8))
            })
        }
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hero())
            addView(steps)
        }
        column.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(0, 0, 0, insets.systemWindowInsetBottom + dpi(16))
            insets
        }
        // A dark strip stays behind the status bar so its light icons read over the scrolling cards
        val statusStrip = View(this).apply { setBackgroundColor(Ui.INK) }
        val root = android.widget.FrameLayout(this).apply {
            addView(ScrollView(context).apply {
                setBackgroundColor(Ui.PAPER)
                isVerticalScrollBarEnabled = false
                addView(column)
            })
            addView(statusStrip, android.widget.FrameLayout.LayoutParams(-1, 0))
        }
        statusStrip.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.layoutParams.height = insets.systemWindowInsetTop
            v.requestLayout()
            insets
        }
        setContentView(root)
        refresh(animate = false)

        // Slide the cards up as the screen opens
        for (i in 0 until steps.childCount) {
            val v = steps.getChildAt(i)
            v.alpha = 0f; v.translationY = dp(40f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(80L + i * 70L).setDuration(480)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }

    private fun hero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Ui.INK)
            val r = dp(32f)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
        }
        val side = dpi(22)
        setPadding(side, dpi(48), side, dpi(26))
        setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            v.setPadding(side, insets.systemWindowInsetTop + dpi(14), side, dpi(26))
            insets
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.akoway_logo) }, LinearLayout.LayoutParams(dpi(30), dpi(30)))
            addView(TextView(context).apply {
                text = "Akoway"; textSize = 18f; typeface = Ui.heavy(); setTextColor(Ui.WHITE); setPadding(dpi(9), 0, 0, 0)
            })
        })
        addView(TextView(context).apply {
            val words = "Let's get you talking."
            text = SpannableString(words).apply {
                setSpan(ForegroundColorSpan(Ui.LIME), words.indexOf("talking"), words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 34f; typeface = Ui.heavy(); setTextColor(Ui.WHITE); setLineSpacing(0f, 0.95f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(22) })
        progressText = TextView(context).apply { textSize = 15f; setTextColor(Ui.MUTED) }
        addView(progressText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
        bar = BarView(context, Ui.CARD_DARK, Ui.LIME)
        addView(bar, LinearLayout.LayoutParams(-1, dpi(14)).apply { topMargin = dpi(14) })
    }

    private fun readyCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.INK, dp(28f))
        setPadding(dpi(20), dpi(20), dpi(20), dpi(20))
        addView(TextView(context).apply {
            val words = "You're ready. Try it."
            text = SpannableString(words).apply {
                setSpan(ForegroundColorSpan(Ui.LIME), words.indexOf("Try"), words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 26f; typeface = Ui.heavy(); setTextColor(Ui.WHITE)
        })
        addView(TextView(context).apply {
            text = "Tap the box, switch to Akoway voice with the keyboard button at the bottom of the screen (or long-press the space bar), and talk."
            textSize = 14.5f; setTextColor(Ui.MUTED); setLineSpacing(0f, 1.15f)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(6) })
        addView(EditText(context).apply {
            hint = "Say something…"
            textSize = 17f
            setTextColor(Ui.INK); setHintTextColor(Ui.MUTED)
            background = Ui.rounded(Ui.WHITE, dp(18f))
            setPadding(dpi(16), dpi(16), dpi(16), dpi(16))
            minLines = 2
            gravity = Gravity.TOP
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(16) })
        addView(Ui.button(context, "Switch keyboard", Ui.LIME, Ui.INK) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
    }

    private fun refresh(animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(column)
        val hasModel = SpeechModel.isReady(this)
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val keyboardOn = isKeyboardOn()
        if (!downloading) model.done(hasModel, "Downloaded. Works offline from now on.")
        mic.done(hasMic, "Allowed.")
        keyboard.done(keyboardOn, "On. Tap the keyboard button at the bottom of the screen, or long-press your keyboard's space bar, to switch to it.")
        val count = listOf(hasModel, hasMic, keyboardOn).count { it }
        progressText.text = if (count == 3) "All set. Akoway works in every app." else "$count of 3 steps done · about a minute"
        bar.setFraction(count / 3f, animate = true)
        ready.visibility = if (count == 3) View.VISIBLE else View.GONE
    }

    private fun startDownload() {
        if (downloading) return
        downloading = true
        TransitionManager.beginDelayedTransition(column)
        model.button.visibility = View.GONE
        modelBar.visibility = View.VISIBLE
        model.detail.text = "Starting…"
        thread(name = "akoway-download") {
            val error = try {
                SpeechModel.download(this, { p ->
                    runOnUiThread {
                        modelBar.setFraction(p, animate = false)
                        model.detail.text = "Downloading… %.0f%% of %d MB".format(p * 100, SpeechModel.downloadBytes / 1_000_000)
                    }
                })
                null
            } catch (e: Exception) {
                e.message ?: "Download failed."
            }
            runOnUiThread {
                downloading = false
                modelBar.visibility = View.GONE
                refresh(animate = true)
                if (error != null) {
                    model.detail.text = "$error Tap Download to resume."
                    model.button.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun isKeyboardOn(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    /** Dark system-bar icons on the light setup screen, light ones on the dark welcome. */
    private fun lightBars(light: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            // The setup screen's top is the dark hero, so only the navigation bar goes dark-on-light
            window.insetsController?.setSystemBarsAppearance(if (light) WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0, mask)
        }
    }

    private fun prefs() = getSharedPreferences("app", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_WELCOMED = "welcomed"
    }
}

/** A rounded progress bar that can glide to a new value. */
class BarView(context: Context, private val track: Int, private val fillColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var fraction = 0f
    private var animator: ValueAnimator? = null

    fun setFraction(f: Float, animate: Boolean) {
        animator?.cancel()
        if (!animate) { fraction = f; invalidate(); return }
        animator = ValueAnimator.ofFloat(fraction, f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { fraction = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = height / 2f
        paint.color = track
        rect.set(0f, 0f, width.toFloat(), height.toFloat()); canvas.drawRoundRect(rect, r, r, paint)
        if (fraction <= 0f) return
        paint.color = fillColor
        rect.set(0f, 0f, maxOf(height.toFloat(), width * fraction), height.toFloat()); canvas.drawRoundRect(rect, r, r, paint)
    }
}
