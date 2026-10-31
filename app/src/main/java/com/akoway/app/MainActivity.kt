package com.akoway.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Setup: download the speech model, allow the microphone, turn on the Akoway accessibility
 * service. Each step shows a tick once done; the screen rechecks whenever it comes back.
 */
class MainActivity : Activity() {

    private inner class Step(number: Int, title: String, detail: String, action: String, onClick: () -> Unit) {
        val status = TextView(this@MainActivity).apply { textSize = 13f; setTextColor(0xFF555555.toInt()) }
        val button = Button(this@MainActivity).apply { text = action; setOnClickListener { onClick() } }
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = card(false)
            addView(TextView(context).apply {
                text = "$number. $title"; textSize = 16f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF0D0D0D.toInt())
            })
            addView(TextView(context).apply { text = detail; textSize = 13f; setTextColor(0xFF555555.toInt()); setPadding(0, dp(4), 0, dp(8)) })
            addView(status)
            addView(button)
        }

        fun done(isDone: Boolean, doneText: String) {
            view.background = card(isDone)
            button.visibility = if (isDone) View.GONE else View.VISIBLE
            if (isDone) status.text = "✓ $doneText"
        }
    }

    private lateinit var model: Step
    private lateinit var mic: Step
    private lateinit var service: Step
    private lateinit var keyboard: Step
    private lateinit var progress: ProgressBar
    private lateinit var ready: TextView
    @Volatile private var downloading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        model = Step(1, "Download the speech model", "Bark 3.0 Clean by Underdog, 240 MB, once. After that Akoway works fully offline and tidies out \"um\"s and repeats.", "Download") { startDownload() }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000; visibility = View.GONE }
        model.view.addView(progress, 3)
        mic = Step(2, "Allow the microphone", "Audio stays on your phone; nothing is sent anywhere.", "Allow") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        service = Step(3, "Turn on Akoway in Accessibility", "This lets Akoway show its tab over other apps and type into the text box you're in. In the list, tap Akoway, then turn it on.", "Open settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        keyboard = Step(4, "Optional: turn on the Akoway keyboard", "For the odd app where the tab can't type into the box. To use it, long-press the space bar on your usual keyboard and pick Akoway voice; it listens straight away and switches back when it's written your words.", "Open settings") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        ready = TextView(this).apply {
            text = "You're all set. In any app, tap the green tab on the right edge of the screen, tap the mic, speak, and tap again."
            textSize = 15f; setTextColor(0xFF0D0D0D.toInt()); setPadding(dp(4), dp(8), dp(4), 0)
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "Welcome to Akoway"; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF0D0D0D.toInt())
                setBackgroundColor(PillView.LIME); setPadding(dp(24), dp(48), dp(24), dp(4))
            })
            addView(TextView(context).apply {
                text = "Offline voice typing for every app."; textSize = 15f; setTextColor(0xFF333333.toInt())
                setBackgroundColor(PillView.LIME); setPadding(dp(24), 0, dp(24), dp(22))
            })
            val steps = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)) }
            for (step in listOf(model, mic, service)) {
                steps.addView(step.view, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
            }
            steps.addView(ready, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
            steps.addView(keyboard.view)
            addView(steps)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFFFFFFFF.toInt()); addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun refresh() {
        val hasModel = SpeechModel.isReady(this)
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val serviceOn = isServiceOn()
        if (!downloading) model.done(hasModel, "Downloaded")
        mic.done(hasMic, "Allowed")
        service.done(serviceOn, "On")
        keyboard.done(isKeyboardOn(), "On. Long-press your keyboard's space bar to switch to it.")
        ready.visibility = if (hasModel && hasMic && serviceOn) View.VISIBLE else View.GONE
    }

    private fun startDownload() {
        if (downloading) return
        downloading = true
        model.button.isEnabled = false
        progress.visibility = View.VISIBLE
        model.status.text = "Starting…"
        thread(name = "akoway-download") {
            val error = try {
                SpeechModel.download(this, { p ->
                    runOnUiThread {
                        progress.progress = (p * 1000).toInt()
                        model.status.text = "Downloading… %.0f%% of %d MB".format(p * 100, SpeechModel.downloadBytes / 1_000_000)
                    }
                })
                null
            } catch (e: Exception) {
                e.message ?: "Download failed."
            }
            runOnUiThread {
                downloading = false
                model.button.isEnabled = true
                progress.visibility = View.GONE
                if (error != null) {
                    model.status.text = "$error Tap Download to resume."
                    model.button.text = "Download"
                }
                refresh()
            }
        }
    }

    private fun isServiceOn(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, AkowayService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun isKeyboardOn(): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == packageName }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun card(done: Boolean) = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()
        setColor(0xFFFFFFFF.toInt())
        setStroke(dp(if (done) 2 else 1), if (done) 0xFF8ED020.toInt() else 0xFFDDDDDD.toInt())
    }

}
