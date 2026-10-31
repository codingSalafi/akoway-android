package com.akoway.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Akoway's colours, type and the small drawing helpers the setup screens share. */
object Ui {
    const val INK = 0xFF060900.toInt()          // near-black with a green cast, as on the website
    const val CARD_DARK = 0xFF1A1C1A.toInt()
    const val LIME = 0xFFC9FE68.toInt()
    const val LIME_DEEP = 0xFF96E600.toInt()
    const val PALE = 0xFFE0FFA6.toInt()
    const val GREEN = 0xFF028700.toInt()
    const val PAPER = 0xFFF3F5EE.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val MUTED = 0xFF8A9A8A.toInt()
    const val SUBTLE = 0xFF5B6658.toInt()
    const val PINK = 0xFFFF9EC7.toInt()

    fun heavy(weight: Int = 800): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else Typeface.DEFAULT_BOLD

    fun Context.dp(v: Float) = v * resources.displayMetrics.density
    fun Context.dpi(v: Int) = (v * resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = INK) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

    /** A big pill button: lime on dark screens, ink on light ones. */
    fun button(context: Context, label: String, fill: Int, textColor: Int, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 17f
        typeface = heavy(700)
        gravity = Gravity.CENTER
        setTextColor(textColor)
        val h = context.dpi(58)
        minHeight = h
        background = rounded(fill, h / 2f)
        setPadding(context.dpi(24), 0, context.dpi(24), 0)
        setOnClickListener {
            it.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                onClick()
            }.start()
        }
    }
}

/** Flat, chunky icons in the onboarding style: lime fills with thick ink outlines. */
class IconView(context: Context, var icon: Icon, private val sizeDp: Int = 56) : View(context) {

    enum class Icon { DOWNLOAD, MIC, KEYBOARD, TAB, CHECK, INFINITY, OFFLINE, LOCK }

    var fillColor = Ui.LIME
        set(v) { field = v; invalidate() }
    var lineColor = Ui.INK
        set(v) { field = v; invalidate() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val r = RectF()
    private val p = Path()

    override fun onMeasure(w: Int, h: Int) {
        val s = (sizeDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(s, s)
    }

    /** Everything is drawn on a 100 x 100 grid. */
    override fun onDraw(c: Canvas) {
        c.save()
        c.scale(width / 100f, height / 100f)
        fill.color = fillColor
        line.color = lineColor
        line.strokeWidth = 7f
        when (icon) {
            Icon.DOWNLOAD -> {
                r.set(14f, 54f, 86f, 86f); c.drawRoundRect(r, 12f, 12f, fill); c.drawRoundRect(r, 12f, 12f, line)
                p.reset(); p.moveTo(50f, 12f); p.lineTo(50f, 62f); c.drawPath(p, line)
                p.reset(); p.moveTo(32f, 45f); p.lineTo(50f, 63f); p.lineTo(68f, 45f); c.drawPath(p, line)
            }
            Icon.MIC -> {
                r.set(34f, 10f, 66f, 62f); c.drawRoundRect(r, 16f, 16f, fill); c.drawRoundRect(r, 16f, 16f, line)
                r.set(22f, 30f, 78f, 76f); c.drawArc(r, 0f, 180f, false, line)
                c.drawLine(50f, 76f, 50f, 90f, line); c.drawLine(36f, 90f, 64f, 90f, line)
            }
            Icon.KEYBOARD -> {
                r.set(8f, 24f, 92f, 78f); c.drawRoundRect(r, 12f, 12f, fill); c.drawRoundRect(r, 12f, 12f, line)
                fill.color = lineColor
                for (row in 0..1) for (col in 0..4) c.drawCircle(24f + col * 13f, 40f + row * 12f, 3.6f, fill)
                c.drawLine(32f, 65f, 68f, 65f, line)
            }
            Icon.TAB -> {
                r.set(22f, 6f, 72f, 94f); c.drawRoundRect(r, 12f, 12f, line)
                r.set(64f, 34f, 84f, 66f); c.drawRoundRect(r, 8f, 8f, fill); c.drawRoundRect(r, 8f, 8f, line)
            }
            Icon.CHECK -> {
                c.drawCircle(50f, 50f, 42f, fill)
                p.reset(); p.moveTo(30f, 51f); p.lineTo(44f, 65f); p.lineTo(71f, 37f)
                line.strokeWidth = 9f; c.drawPath(p, line)
            }
            Icon.INFINITY -> {
                p.reset()
                for (i in 0..72) {   // lemniscate of Bernoulli
                    val t = i / 72.0 * 2 * Math.PI
                    val d = 1 + Math.sin(t) * Math.sin(t)
                    val x = (50 + 38 * Math.cos(t) / d).toFloat()
                    val y = (50 + 38 * Math.sin(t) * Math.cos(t) / d).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                line.strokeWidth = 11f; c.drawPath(p, line)
            }
            Icon.OFFLINE -> {
                for (rad in listOf(40f, 27f)) {
                    r.set(50f - rad, 64f - rad, 50f + rad, 64f + rad)
                    c.drawArc(r, 220f, 100f, false, line)
                }
                c.drawCircle(50f, 64f, 8f, fill.apply { color = lineColor })
                line.strokeWidth = 8f; c.drawLine(18f, 14f, 82f, 86f, line)
            }
            Icon.LOCK -> {
                r.set(30f, 12f, 70f, 60f); c.drawArc(r, 180f, 180f, false, line)
                c.drawLine(30f, 36f, 30f, 46f, line); c.drawLine(70f, 36f, 70f, 46f, line)
                r.set(16f, 44f, 84f, 92f); c.drawRoundRect(r, 12f, 12f, fill); c.drawRoundRect(r, 12f, 12f, line)
                c.drawCircle(50f, 64f, 6f, Paint(fill).apply { color = lineColor }); c.drawLine(50f, 66f, 50f, 78f, line)
            }
        }
        c.restore()
    }
}
