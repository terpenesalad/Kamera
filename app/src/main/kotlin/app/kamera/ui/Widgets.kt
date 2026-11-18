package app.kamera.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmKind
import app.kamera.film.FilmStock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A compact text toggle used for formats, modes and tabs. */
class Chip(context: Context, label: String) : TextView(context) {
    var selectedChip: Boolean = false
        set(value) {
            field = value
            refresh()
        }

    init {
        text = label
        textSize = 12f
        typeface = Type.label
        letterSpacing = 0.06f
        gravity = Gravity.CENTER
        includeFontPadding = false
        setPadding(context.dp(12), context.dp(8), context.dp(12), context.dp(8))
        isClickable = true
        refresh()
    }

    private fun refresh() {
        setTextColor(if (selectedChip) Palette.amber else Palette.muted)
        background = rounded(
            if (selectedChip) Palette.raised else Color.TRANSPARENT,
            context.dpf(16),
            if (selectedChip) Palette.amber else Palette.line,
            context.dp(1),
        )
    }
}

/** A row of mutually exclusive chips that scrolls when it does not fit. */
class ChipGroup(context: Context) : HorizontalScrollView(context) {
    private val row = context.row()
    private val chips = ArrayList<Chip>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
    }

    fun setOptions(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
        row.removeAllViews()
        chips.clear()
        labels.forEachIndexed { index, label ->
            val chip = Chip(context, label)
            chip.selectedChip = index == selected
            chip.setOnClickListener {
                select(index)
                onSelect(index)
            }
            chips.add(chip)
            row.addView(chip, linearParams(WRAP, WRAP).apply { marginEnd = context.dp(8) })
        }
    }

    fun select(index: Int) = chips.forEachIndexed { i, chip -> chip.selectedChip = i == index }
}

/**
 * A labelled editing slider. The track fills from the neutral value, and a double tap resets it.
 */
@SuppressLint("ClickableViewAccessibility")
class ValueSlider(
    context: Context,
    private val label: String,
    private val min: Float,
    private val max: Float,
    private val neutral: Float,
    private val format: (Float) -> String = { v -> formatSigned(v * 100f) },
    private val onChange: (Float) -> Unit,
) : View(context) {
    var value: Float = neutral
        private set

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.text
        textSize = context.dpf(13)
        typeface = Type.body
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dpf(12)
        typeface = Type.mono
        textAlign = Paint.Align.RIGHT
    }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dpf(2); strokeCap = Paint.Cap.ROUND }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    private var dragging = false

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                setValue(neutral, notify = true)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                return true
            }
        },
    )

    fun setValue(newValue: Float, notify: Boolean = false) {
        val clamped = newValue.coerceIn(min, max)
        if (clamped == value) return
        value = clamped
        invalidate()
        if (notify) onChange(clamped)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(58))
    }

    private val inset get() = context.dpf(10)

    private fun xFor(v: Float): Float = inset + (width - 2 * inset) * (v - min) / (max - min)

    override fun onDraw(canvas: Canvas) {
        val textY = context.dpf(20)
        val trackY = context.dpf(41)
        canvas.drawText(label, inset, textY, labelPaint)
        val changed = abs(value - neutral) > 1e-4f
        valuePaint.color = if (changed) Palette.signal else Palette.muted
        canvas.drawText(format(value), width - inset, textY, valuePaint)
        track.color = Palette.line
        canvas.drawLine(inset, trackY, width - inset, trackY, track)
        track.color = if (changed) Palette.signal else Palette.muted
        canvas.drawLine(xFor(neutral), trackY, xFor(value), trackY, track)
        knob.color = if (dragging) Palette.signal else Palette.text
        canvas.drawCircle(xFor(value), trackY, context.dpf(if (dragging) 9 else 7), knob)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val t = ((event.x - inset) / (width - 2 * inset)).coerceIn(0f, 1f)
                var v = min + t * (max - min)
                // A gentle detent makes it easy to land exactly on neutral.
                if (abs(v - neutral) < (max - min) * 0.012f) v = neutral
                setValue(v, notify = true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }

    companion object {
        fun formatSigned(v: Float): String {
            val rounded = v.roundToInt()
            return if (rounded > 0) "+$rounded" else rounded.toString()
        }
    }
}

/** A ruled scale for dialling in a manual value, like the top plate of a film camera. */
@SuppressLint("ClickableViewAccessibility")
class ScaleDial(context: Context) : View(context) {
    private var labels: List<String> = emptyList()
    private var index = 0
    private var offset = 0f
    private var dragStartX = 0f
    private var dragStartOffset = 0f
    private var onSelect: (Int) -> Unit = {}

    private val spacing = context.dpf(44)
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dpf(1.2f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.dpf(11)
        typeface = Type.mono
    }

    fun configure(options: List<String>, selected: Int, listener: (Int) -> Unit) {
        labels = options
        index = selected.coerceIn(0, max(options.size - 1, 0))
        offset = index * spacing
        onSelect = listener
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val centre = width / 2f
        val first = max(0, ((offset - centre) / spacing).toInt() - 1)
        val last = min(labels.size - 1, ((offset + centre) / spacing).toInt() + 1)
        for (i in first..last) {
            val x = centre + i * spacing - offset
            val focus = 1f - min(abs(x - centre) / (width * 0.5f), 1f)
            val current = i == index
            tick.color = if (current) Palette.signal else Palette.faint
            canvas.drawLine(x, 0f, x, context.dpf(if (current) 14 else 9), tick)
            textPaint.color = if (current) Palette.signal else Palette.muted
            textPaint.alpha = (90 + 165 * focus).toInt()
            canvas.drawText(labels[i], x, context.dpf(32), textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartX = event.x
                dragStartOffset = offset
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                offset = (dragStartOffset - (event.x - dragStartX)).coerceIn(0f, max(labels.size - 1, 0) * spacing)
                val nearest = (offset / spacing).roundToInt()
                if (nearest != index) {
                    index = nearest
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onSelect(index)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (abs(event.x - dragStartX) < context.dpf(6) && event.actionMasked == MotionEvent.ACTION_UP) {
                    val tapped = ((offset + event.x - width / 2f) / spacing).roundToInt().coerceIn(0, max(labels.size - 1, 0))
                    if (tapped != index) {
                        index = tapped
                        onSelect(index)
                    }
                }
                offset = index * spacing
                invalidate()
            }
        }
        return true
    }
}

/** Box art for a film stock, drawn in code so each roll is recognisable at a glance. */
class FilmBox(context: Context, val stock: FilmStock) : View(context) {
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Type.condensed }
    private val isoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Type.condensed }
    private val rect = RectF()

    private val main = dimmed(stock.boxColor)
    private val accent = dimmed(stock.stripeColor)
    private val ink = if (Color.luminance(main) > 0.16f) Color.rgb(8, 8, 8) else Palette.text

    /** Box art is kept low-key so the film cabinet never glares in the dark. */
    private fun dimmed(rgb: Int): Int =
        Color.rgb((rgb shr 16 and 255) * 11 / 20, (rgb shr 8 and 255) * 11 / 20, (rgb and 255) * 11 / 20)

    override fun onDraw(canvas: Canvas) {
        val radius = context.dpf(6)
        val pad = context.dpf(3)
        rect.set(pad, pad, width - pad, height - pad)
        fill.color = main
        canvas.drawRoundRect(rect, radius, radius, fill)
        stripe.color = accent
        val stripeTop = height * 0.60f
        canvas.save()
        canvas.clipRect(rect)
        canvas.drawRect(pad, stripeTop, width - pad, stripeTop + height * 0.11f, stripe)
        canvas.restore()

        titlePaint.color = ink
        titlePaint.textSize = width * 0.15f
        val words = stock.name.split(" ")
        canvas.drawText(words.first().uppercase(), pad * 3, height * 0.26f, titlePaint)
        isoPaint.color = ink
        isoPaint.textSize = width * 0.34f
        canvas.drawText(words.drop(1).joinToString(" "), pad * 3, height * 0.54f, isoPaint)
        titlePaint.textSize = width * 0.095f
        canvas.drawText(stock.kind.label.uppercase(), pad * 3, height * 0.87f, titlePaint)

        outline.color = if (chosen) Palette.signal else Palette.line
        outline.strokeWidth = context.dpf(if (chosen) 2.5f else 1f)
        canvas.drawRoundRect(rect, radius, radius, outline)
    }
}

/** The film cabinet: every stock, grouped by family, in one sideways-scrolling strip. */
class FilmStrip(
    context: Context,
    stocks: List<FilmStock> = FilmCatalog.all,
    private val onPick: (FilmStock) -> Unit,
) : HorizontalScrollView(context) {
    private val boxes = ArrayList<FilmBox>()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        val row = context.row(Gravity.TOP).apply { setPadding(context.dp(12), 0, context.dp(12), 0) }
        for (kind in FilmKind.entries) {
            val group = stocks.filter { it.kind == kind }
            if (group.isEmpty()) continue
            val section = context.column()
            section.addView(
                context.text(kind.label.uppercase(), 10f, Palette.muted, Type.label),
                linearParams(WRAP, context.dp(22)),
            )
            val items = context.row()
            for (stock in group) {
                val box = FilmBox(context, stock)
                box.setOnClickListener {
                    choose(stock.id)
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    onPick(stock)
                }
                boxes.add(box)
                items.addView(box, linearParams(context.dp(92), context.dp(118)).apply { marginEnd = context.dp(6) })
            }
            section.addView(items)
            row.addView(section, linearParams(WRAP, WRAP).apply { marginEnd = context.dp(14) })
        }
        addView(row, FrameLayout.LayoutParams(WRAP, WRAP))
    }

    fun choose(id: String) {
        boxes.forEach { it.chosen = it.stock.id == id }
        val box = boxes.firstOrNull { it.stock.id == id } ?: return
        post {
            val parentSection = box.parent?.parent as? View ?: return@post
            val x = parentSection.left + box.left + box.width / 2 - width / 2
            smoothScrollTo(x, 0)
        }
    }
}

/** A labelled on/off setting row. */
class ToggleRow(context: Context, label: String, initial: Boolean, onChange: (Boolean) -> Unit) : LinearLayout(context) {
    private var on = initial
    private val state = context.text("", 12f, Palette.muted, Type.mono, Gravity.END or Gravity.CENTER_VERTICAL)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(context.text(label, 14f), linearParams(0, context.dp(46), 1f))
        addView(state, linearParams(WRAP, context.dp(46)))
        refresh()
        isClickable = true
        setOnClickListener {
            on = !on
            refresh()
            onChange(on)
        }
    }

    private fun refresh() {
        state.text = if (on) "ON" else "OFF"
        state.setTextColor(if (on) Palette.signal else Palette.muted)
    }
}
