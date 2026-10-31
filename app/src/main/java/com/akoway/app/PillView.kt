package com.akoway.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos

/**
 * The floating pill, drawn like the Windows overlay: outer pill in the state colour, pale inner
 * pill, a state circle with its icon on the left and a close button on the right.
 * All drawing happens in the Figma frame's 400 x 227 units, scaled to the view size.
 */
class PillView(context: Context) : View(context) {

    enum class Look { IDLE, LISTENING, PROCESSING, WRITING, ERROR }

    var look = Look.IDLE
        set(value) {
            field = value
            invalidate()
        }

    /** Drawn width; the height follows the frame's proportions. */
    var widthDp = WIDTH_DP

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shapes = HashMap<PillPaths.Shape, Path>()
    private val rect = RectF()

    /** True if (x, y) in view pixels is on the mic circle. */
    fun isOnCircle(x: Float, y: Float): Boolean {
        val s = width / FRAME_W
        val dx = x / s - CIRCLE_X
        val dy = y / s - CIRCLE_Y
        return dx * dx + dy * dy <= (CIRCLE_R + 12) * (CIRCLE_R + 12)
    }

    /** True if (x, y) in view pixels is on the close button. */
    fun isOnClose(x: Float, y: Float): Boolean {
        val s = width / FRAME_W
        return x / s in 240f..345f && y / s in 60f..170f
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (widthDp * resources.displayMetrics.density).toInt()
        setMeasuredDimension(w, (w * FRAME_H / FRAME_W).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val s = width / FRAME_W
        canvas.save()
        canvas.scale(s, s)

        val active = look == Look.LISTENING || look == Look.PROCESSING || look == Look.WRITING
        val outer = when (look) {
            Look.ERROR -> RED
            Look.IDLE -> LIME
            else -> LIME_ACTIVE
        }
        fill(outer); rect.set(0f, 0f, FRAME_W, FRAME_H); canvas.drawRoundRect(rect, 113.5f, 113.5f, paint)
        fill(PALE); rect.set(24f, 24f, FRAME_W - 24f, FRAME_H - 24f); canvas.drawRoundRect(rect, 89.5f, 89.5f, paint)

        fill(
            when (look) {
                Look.WRITING -> BLACK
                Look.LISTENING, Look.PROCESSING -> GREEN
                else -> LIME
            }
        )
        canvas.drawCircle(CIRCLE_X, CIRCLE_Y, CIRCLE_R, paint)

        val now = SystemClock.uptimeMillis()
        when (look) {
            Look.PROCESSING -> {
                fill(PALE, 0.5f); draw(canvas, PillPaths.SPIN_TRACK)
                canvas.save()
                canvas.rotate((now % 900) / 900f * 360f, 125.396f, 114.396f)
                fill(PALE); draw(canvas, PillPaths.SPIN_ARC)
                canvas.restore()
            }
            Look.WRITING -> {
                fill(PALE)
                draw(canvas, PillPaths.WRITE_HAND); draw(canvas, PillPaths.WRITE_DOT); draw(canvas, PillPaths.WRITE_LINE)
            }
            else -> {
                fill(if (active) PALE else GREEN)
                draw(canvas, PillPaths.MIC_BODY); draw(canvas, PillPaths.MIC_STAND)
                if (look == Look.LISTENING) {
                    // Pulsing red recording dot: fades to 15% and back every 1.1 s
                    val phase = (now % 1100) / 1100.0
                    fill(RED, (0.575 + 0.425 * cos(phase * 2 * PI)).toFloat())
                    canvas.drawCircle(140f, 84f, 9f, paint)
                }
            }
        }

        fill(CLOSE_GREY); draw(canvas, PillPaths.CLOSE)
        canvas.restore()

        if (look == Look.LISTENING || look == Look.PROCESSING) postInvalidateOnAnimation()
    }

    private fun fill(color: Int, alpha: Float = 1f) {
        paint.color = color
        paint.alpha = (alpha * 255).toInt()
    }

    private fun draw(canvas: Canvas, shape: PillPaths.Shape) {
        val path = shapes.getOrPut(shape) { SvgPath.parse(shape.data).apply { offset(shape.dx, shape.dy) } }
        canvas.drawPath(path, paint)
    }

    companion object {
        const val WIDTH_DP = 150f
        private const val FRAME_W = 400f
        private const val FRAME_H = 227f
        private const val CIRCLE_X = 125f
        private const val CIRCLE_Y = 114f
        private const val CIRCLE_R = 59f

        const val LIME = 0xFFC9FE68.toInt()
        private const val LIME_ACTIVE = 0xFF96E600.toInt()
        private const val PALE = 0xFFE0FFA6.toInt()
        private const val GREEN = 0xFF028700.toInt()
        private const val BLACK = 0xFF000000.toInt()
        private const val RED = 0xFFE53935.toInt()
        private const val CLOSE_GREY = 0xFF2E2E2E.toInt()
    }
}

/** Parses the absolute M / L / C / Z path data the Figma export uses. */
object SvgPath {
    fun parse(data: String): Path {
        val path = Path()
        val tokens = Regex("[MLCZ]|-?[0-9.]+(?:e-?[0-9]+)?").findAll(data).map { it.value }.toList()
        var i = 0
        var command = 'M'
        fun num() = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                command = t[0]; i++
                if (command == 'Z') { path.close(); continue }
            }
            when (command) {
                'M' -> { path.moveTo(num(), num()); command = 'L' }
                'L' -> path.lineTo(num(), num())
                'C' -> path.cubicTo(num(), num(), num(), num(), num(), num())
                else -> i++
            }
        }
        return path
    }
}
