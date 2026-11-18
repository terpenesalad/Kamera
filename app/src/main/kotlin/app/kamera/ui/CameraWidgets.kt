package app.kamera.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.DecelerateInterpolator
import app.kamera.film.FrameGeometry
import kotlin.math.abs
import kotlin.math.min

/** The shutter release. White for stills, red for motion, and a square while recording. */
class ShutterButton(context: Context) : View(context) {
    var videoMode = false
        set(value) {
            field = value
            invalidate()
        }
    var recording = false
        set(value) {
            field = value
            invalidate()
        }
    var busy = false
        set(value) {
            field = value
            invalidate()
        }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = min(width, height) / 2f - context.dpf(3)
        ring.strokeWidth = context.dpf(3)
        ring.color = if (busy) Palette.faint else Palette.text
        canvas.drawCircle(cx, cy, outer, ring)
        disc.color = when {
            videoMode -> Palette.record
            isPressed -> Palette.muted
            else -> Palette.text
        }
        if (recording) {
            val half = outer * 0.42f
            rect.set(cx - half, cy - half, cx + half, cy + half)
            canvas.drawRoundRect(rect, context.dpf(6), context.dpf(6), disc)
        } else {
            canvas.drawCircle(cx, cy, outer * if (isPressed) 0.74f else 0.82f, disc)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** Everything drawn over the live picture: frame edge, guides, focus mark and assists. */
class FinderOverlay(context: Context) : View(context) {
    /** Long edge over short edge of the frame being composed. */
    var longAspect = 1.5f
        set(value) {
            field = value
            invalidate()
        }
    var showGrid = false
        set(value) {
            field = value
            invalidate()
        }
    var showLevel = true
        set(value) {
            field = value
            invalidate()
        }
    var levelDegrees = 0f
        set(value) {
            field = value
            if (showLevel) invalidate()
        }
    var histogram: FloatArray? = null
        set(value) {
            field = value
            invalidate()
        }

    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val histPath = Path()
    private val frame = RectF()

    private var focusX = 0f
    private var focusY = 0f
    private var focusProgress = 1f
    private var blackout = 0f

    /** The on-screen rectangle of the picture, matching what the renderer draws. */
    fun frameRect(): RectF {
        if (width == 0 || height == 0) return frame.apply { setEmpty() }
        val aspect = if (height >= width) 1f / longAspect else longAspect
        val (w, h) = FrameGeometry.fit(width, height, aspect)
        val left = (width - w) / 2f
        val top = (height - h) / 2f
        frame.set(left, top, left + w, top + h)
        return frame
    }

    fun showFocusAt(x: Float, y: Float) {
        focusX = x
        focusY = y
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                focusProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** A brief blackout, the reassuring blink of a mirror. */
    fun blink() {
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 160
            addUpdateListener {
                blackout = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = frameRect()
        if (r.isEmpty) return
        hairline.strokeWidth = context.dpf(0.8f)

        if (showGrid) {
            hairline.color = Color.argb(110, 255, 255, 255)
            for (i in 1..2) {
                val x = r.left + r.width() * i / 3f
                val y = r.top + r.height() * i / 3f
                canvas.drawLine(x, r.top, x, r.bottom, hairline)
                canvas.drawLine(r.left, y, r.right, y, hairline)
            }
        }

        if (showLevel) {
            val level = abs(levelDegrees) < 0.7f
            hairline.color = if (level) Palette.amber else Color.argb(150, 255, 255, 255)
            hairline.strokeWidth = context.dpf(1.2f)
            val half = r.width() * 0.13f
            canvas.save()
            canvas.rotate(levelDegrees, r.centerX(), r.centerY())
            canvas.drawLine(r.centerX() - half, r.centerY(), r.centerX() - half * 0.3f, r.centerY(), hairline)
            canvas.drawLine(r.centerX() + half * 0.3f, r.centerY(), r.centerX() + half, r.centerY(), hairline)
            canvas.restore()
            hairline.strokeWidth = context.dpf(0.8f)
        }

        histogram?.let { drawHistogram(canvas, r, it) }

        if (focusProgress < 1f) {
            val size = context.dpf(38) * (1.35f - 0.35f * min(focusProgress * 3f, 1f))
            hairline.color = Palette.amber
            hairline.alpha = ((1f - focusProgress) * 2f * 255).toInt().coerceIn(0, 255)
            hairline.strokeWidth = context.dpf(1.4f)
            canvas.drawRect(focusX - size, focusY - size, focusX + size, focusY + size, hairline)
            hairline.alpha = 255
        }

        if (blackout > 0f) {
            fill.color = Color.argb((blackout * 255).toInt(), 0, 0, 0)
            canvas.drawRect(r, fill)
        }
    }

    private fun drawHistogram(canvas: Canvas, r: RectF, bins: FloatArray) {
        val w = context.dpf(92)
        val h = context.dpf(44)
        val left = r.left + context.dpf(10)
        val bottom = r.top + context.dpf(10) + h
        fill.color = Color.argb(120, 0, 0, 0)
        canvas.drawRect(left, bottom - h, left + w, bottom, fill)
        histPath.reset()
        histPath.moveTo(left, bottom)
        bins.forEachIndexed { i, v -> histPath.lineTo(left + w * i / (bins.size - 1), bottom - h * v) }
        histPath.lineTo(left + w, bottom)
        histPath.close()
        fill.color = Palette.muted
        canvas.drawPath(histPath, fill)
    }
}
