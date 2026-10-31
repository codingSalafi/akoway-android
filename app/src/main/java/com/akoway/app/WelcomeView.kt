package com.akoway.app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.akoway.app.Ui.dp
import com.akoway.app.Ui.dpi

/**
 * The first-run welcome: the pill acts out a dictation (it hears "um"s and repeats, then types the
 * tidy sentence), the promises tumble in as pills, and one button leads to setup.
 */
@SuppressLint("ViewConstructor")
class WelcomeView(context: Context, private val onDone: () -> Unit) : LinearLayout(context) {

    private val main = Handler(Looper.getMainLooper())
    private val pill = PillView(context).apply { widthDp = 230f }
    private val heard = TextView(context)
    private val typed = TextView(context)
    private val pile = FrameLayout(context)
    private val reveal = ArrayList<View>()
    private var example = 0
    private var running = false

    private class Example(val spoken: String, val written: String)

    private val examples = listOf(
        Example("um, so meet me at the, the gate by six", "Meet me at the gate by six."),
        Example("can you uh send me the the report tonight", "Can you send me the report tonight?"),
        Example("thanks so much, um, see you tomorrow", "Thanks so much, see you tomorrow!"),
    )

    private class Promise(val text: String, val fill: Int, val ink: Int, val outline: Boolean = false)

    private val promises = listOf(
        Promise("Free, forever", Ui.LIME, Ui.INK),
        Promise("Works offline", Ui.WHITE, Ui.INK),
        Promise("Your voice stays on your phone", Ui.GREEN, Ui.WHITE),
        Promise("No account", Ui.PALE, Ui.INK),
        Promise("Every app", Ui.INK, Ui.LIME, outline = true),
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.INK)
        clipChildren = false     // the promise pills fall in from above their pile
        pile.clipChildren = false
        val side = context.dpi(24)
        setPadding(side, 0, side, context.dpi(20))

        // Logo and name
        val brand = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply { setImageResource(R.drawable.akoway_logo) }, LayoutParams(context.dpi(34), context.dpi(34)))
            addView(TextView(context).apply {
                text = "Akoway"; textSize = 20f; typeface = Ui.heavy(); setTextColor(Ui.WHITE)
                setPadding(context.dpi(10), 0, 0, 0)
            })
        }
        addView(brand, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(12) })

        // The little dictation demo
        val demo = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(pill)
            heard.apply {
                textSize = 17f; setTextColor(Ui.MUTED); gravity = Gravity.CENTER
                typeface = resources.getFont(R.font.instrument_serif)
                setPadding(0, context.dpi(14), 0, context.dpi(10))
                minLines = 2
            }
            addView(heard, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            typed.apply {
                textSize = 19f; setTextColor(Ui.WHITE); typeface = Ui.heavy(600)
                background = Ui.rounded(Ui.CARD_DARK, context.dp(20f))
                setPadding(context.dpi(18), context.dpi(16), context.dpi(18), context.dpi(16))
                minLines = 1
                hint = "Your words land here"
                setHintTextColor(0xFF3A4038.toInt())
            }
            addView(typed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        addView(demo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(34) })

        addView(View(context), LayoutParams(1, 0, 1f))

        // Promises tumble into a pile here
        addView(pile, LayoutParams(LayoutParams.MATCH_PARENT, context.dpi(196)))

        val headline = TextView(context).apply {
            val words = "Talk, and it's typed."
            text = SpannableString(words).apply {
                setSpan(ForegroundColorSpan(Ui.LIME), words.indexOf("it's"), words.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            textSize = 40f; typeface = Ui.heavy(); setTextColor(Ui.WHITE)
            setLineSpacing(0f, 0.95f)
        }
        addView(headline, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(18) })
        val sub = TextView(context).apply {
            text = "Voice typing for every app, on your phone. No internet, no account, nothing you say ever leaves it."
            textSize = 16f; setTextColor(Ui.MUTED); setLineSpacing(0f, 1.15f)
        }
        addView(sub, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(10) })
        val go = Ui.button(context, "Get started", Ui.LIME, Ui.INK) { stop(); onDone() }
        addView(go, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dpi(22) })

        reveal += listOf(brand, demo, headline, sub, go)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) return
        running = true
        // Fade everything up in turn, then start the demo and drop the promises
        val lift = context.dp(28f)
        reveal.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = lift
            v.animate().alpha(1f).translationY(0f).setStartDelay(120L + i * 110L).setDuration(520)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
        main.postDelayed({ playExample() }, 700)
        pile.post { dropPromises() }
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    private fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
    }

    // ── The dictation demo ────────────────────────────────────────────────────

    private fun playExample() {
        if (!running) return
        val ex = examples[example++ % examples.size]
        pill.look = PillView.Look.LISTENING
        typed.text = ""
        heard.text = ""
        heard.alpha = 1f
        // Words arrive as if spoken
        val words = ex.spoken.split(" ")
        words.forEachIndexed { i, _ ->
            main.postDelayed({ heard.text = "“" + words.take(i + 1).joinToString(" ") + "”" }, 250L + i * 230L)
        }
        val spokenMs = 250L + words.size * 230L + 350L
        main.postDelayed({ pill.look = PillView.Look.PROCESSING }, spokenMs)
        main.postDelayed({
            pill.look = PillView.Look.WRITING
            heard.animate().alpha(0.35f).setDuration(300).start()
            typeOut(ex.written)
        }, spokenMs + 700)
        val doneMs = spokenMs + 700 + ex.written.length * 32L + 300
        main.postDelayed({ pill.look = PillView.Look.IDLE }, doneMs)
        main.postDelayed({ playExample() }, doneMs + 1900)
    }

    private fun typeOut(text: String) {
        for (i in 1..text.length) {
            main.postDelayed({ typed.text = text.substring(0, i) + if (i < text.length) "▌" else "" }, i * 32L)
        }
    }

    // ── Promises ──────────────────────────────────────────────────────────────

    private fun dropPromises() {
        val w = pile.width.toFloat()
        val h = pile.height.toFloat()
        val chipH = context.dp(54f)
        val gap = context.dp(4f)
        val chips = promises.map { chip(it) }
        chips.forEach { pile.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, chipH.toInt())) }
        pile.post {
            // Where each one comes to rest: a loose pile, three rows from the bottom up
            fun row(n: Int) = h - chipH - n * (chipH - gap)
            val cw = chips.map { it.width.toFloat() }
            val rest = listOf(
                Triple(0f, row(0), -3f),
                Triple(w - cw[1], row(0), 4f),
                Triple((w - cw[2]) / 2f, row(1), -2f),
                Triple(w * 0.04f, row(2), 6f),
                Triple(w - cw[4] - w * 0.06f, row(2), -7f),
            )
            chips.forEachIndexed { i, chip ->
                val (x, y, rot) = rest[i]
                chip.x = x; chip.y = -chipH * 3 - i * chipH; chip.rotation = rot * 4
                chip.animate().y(y).rotation(rot).setStartDelay(900L + i * 170L).setDuration(650)
                    .setInterpolator(OvershootInterpolator(1.1f)).start()
            }
        }
    }

    private fun chip(p: Promise) = TextView(context).apply {
        text = p.text
        textSize = 17f
        typeface = Ui.heavy(700)
        gravity = Gravity.CENTER
        setTextColor(p.ink)
        val h = context.dp(54f)
        background = Ui.rounded(p.fill, h / 2, if (p.outline) context.dpi(2) else 0, Ui.LIME)
        setPadding(context.dpi(20), 0, context.dpi(20), 0)
    }
}
