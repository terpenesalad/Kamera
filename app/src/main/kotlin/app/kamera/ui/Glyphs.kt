package app.kamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Line icons drawn on a 24-unit grid, so no bitmap or font assets are needed. */
enum class Glyph(val build: Path.() -> Unit) {
    BACK({ moveTo(15f, 5f); lineTo(8f, 12f); lineTo(15f, 19f) }),
    CLOSE({ moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }),
    CHECK({ moveTo(5f, 13f); lineTo(10f, 18f); lineTo(19f, 7f) }),
    PLUS({ moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }),
    FLASH({ moveTo(13f, 3f); lineTo(6f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 21f); lineTo(18f, 10f); lineTo(12.5f, 10f); close() }),
    FLASH_OFF({
        moveTo(13f, 3f); lineTo(6f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 21f); lineTo(18f, 10f); lineTo(12.5f, 10f); close()
        moveTo(4f, 4f); lineTo(20f, 20f)
    }),
    FLIP({
        moveTo(4f, 10f); arcTo(RectF(4f, 4f, 20f, 20f), 180f, 150f); moveTo(16f, 3f); lineTo(18f, 6.5f); lineTo(14f, 7.5f)
        moveTo(20f, 14f); arcTo(RectF(4f, 4f, 20f, 20f), 0f, 150f); moveTo(8f, 21f); lineTo(6f, 17.5f); lineTo(10f, 16.5f)
    }),
    SETTINGS({
        moveTo(4f, 7f); lineTo(13f, 7f); moveTo(18f, 7f); lineTo(20f, 7f); addCircle(15.5f, 7f, 2.5f, Path.Direction.CW)
        moveTo(4f, 17f); lineTo(6f, 17f); moveTo(11f, 17f); lineTo(20f, 17f); addCircle(8.5f, 17f, 2.5f, Path.Direction.CW)
    }),
    SHARE({
        moveTo(12f, 15f); lineTo(12f, 3f); moveTo(7.5f, 7.5f); lineTo(12f, 3f); lineTo(16.5f, 7.5f)
        moveTo(5f, 12f); lineTo(5f, 20f); lineTo(19f, 20f); lineTo(19f, 12f)
    }),
    TRASH({
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(9f, 7f); lineTo(9f, 4f); lineTo(15f, 4f); lineTo(15f, 7f)
        moveTo(6f, 7f); lineTo(7f, 20f); lineTo(17f, 20f); lineTo(18f, 7f); moveTo(10f, 11f); lineTo(10f, 16f); moveTo(14f, 11f); lineTo(14f, 16f)
    }),
    EDIT({
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        addCircle(9f, 7f, 1.8f, Path.Direction.CW); addCircle(16f, 12f, 1.8f, Path.Direction.CW); addCircle(7f, 17f, 1.8f, Path.Direction.CW)
    }),
    ROTATE({
        moveTo(5f, 12f); arcTo(RectF(5f, 5f, 19f, 19f), 180f, 270f); moveTo(5f, 7f); lineTo(5f, 12f); lineTo(9.5f, 11f)
    }),
    MIRROR({
        moveTo(12f, 3f); lineTo(12f, 21f); moveTo(9f, 7f); lineTo(3f, 17f); lineTo(9f, 17f); close()
        moveTo(15f, 7f); lineTo(21f, 17f); lineTo(15f, 17f); close()
    }),
    COMPARE({
        addRect(RectF(4f, 5f, 20f, 19f), Path.Direction.CW); moveTo(12f, 3f); lineTo(12f, 21f)
    }),
    IMPORT({
        moveTo(12f, 3f); lineTo(12f, 15f); moveTo(7.5f, 10.5f); lineTo(12f, 15f); lineTo(16.5f, 10.5f)
        moveTo(5f, 20f); lineTo(19f, 20f)
    }),
    FILTER({
        addRect(RectF(5f, 4f, 19f, 20f), Path.Direction.CW); moveTo(5f, 9f); lineTo(19f, 9f); moveTo(5f, 12.5f); lineTo(19f, 12.5f)
    }),
    PLAY({ moveTo(8f, 5f); lineTo(19f, 12f); lineTo(8f, 19f); close() }),
    PAUSE({ moveTo(9f, 5f); lineTo(9f, 19f); moveTo(15f, 5f); lineTo(15f, 19f) }),
}

/** A square icon button that highlights while active. */
class GlyphButton(context: Context, glyph: Glyph) : View(context) {
    var glyph: Glyph = glyph
        set(value) {
            field = value
            rebuild()
            invalidate()
        }

    var active: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var tint: Int = Palette.text
        set(value) {
            field = value
            invalidate()
        }

    private val path = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.7f
    }

    init {
        rebuild()
        isClickable = true
        pressDims()
    }

    private fun rebuild() {
        path.reset()
        glyph.build(path)
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        val icon = minOf(side * 0.62f, context.dpf(26))
        val scale = icon / 24f
        canvas.save()
        canvas.translate((width - icon) / 2f, (height - icon) / 2f)
        canvas.scale(scale, scale)
        paint.color = if (active) Palette.signal else tint
        canvas.drawPath(path, paint)
        canvas.restore()
    }
}
