package app.kamera.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Kamera's visual language: a dark instrument panel with one signal colour. */
object Palette {
    val background = Color.rgb(9, 9, 10)
    val surface = Color.rgb(20, 20, 22)
    val raised = Color.rgb(32, 32, 35)
    val line = Color.rgb(50, 50, 54)
    val text = Color.rgb(238, 233, 221)
    val muted = Color.rgb(146, 143, 135)
    val faint = Color.rgb(92, 90, 85)
    val signal = Color.rgb(242, 92, 42)
    val amber = Color.rgb(244, 183, 64)
    val record = Color.rgb(232, 44, 36)
}

object Type {
    val label: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val body: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val mono: Typeface = Typeface.MONOSPACE
}

fun Context.dp(value: Number): Int = (value.toFloat() * resources.displayMetrics.density).roundToInt()

fun Context.dpf(value: Number): Float = value.toFloat() * resources.displayMetrics.density

fun Context.text(
    value: CharSequence = "",
    sizeSp: Float = 14f,
    color: Int = Palette.text,
    typeface: Typeface = Type.body,
    gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    this.typeface = typeface
    this.gravity = gravity
    includeFontPadding = false
}

fun rounded(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radiusPx
    if (strokePx > 0) setStroke(strokePx, strokeColor)
}

/** Layout-parameter shorthands, used because every screen is built in code. */
fun linearParams(width: Int, height: Int, weight: Float = 0f, gravity: Int = -1) =
    LinearLayout.LayoutParams(width, height, weight).apply { this.gravity = gravity }

fun frameParams(width: Int, height: Int, gravity: Int = Gravity.NO_GRAVITY) =
    FrameLayout.LayoutParams(width, height, gravity)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.row(gravity: Int = Gravity.CENTER_VERTICAL, build: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    this.gravity = gravity
    build()
}

fun Context.column(gravity: Int = Gravity.NO_GRAVITY, build: LinearLayout.() -> Unit = {}) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    this.gravity = gravity
    build()
}

/** Gives touch feedback by dimming a view while it is pressed. */
fun View.pressDims() {
    setOnTouchListener { v, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> v.alpha = 0.55f
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> v.alpha = 1f
        }
        false
    }
}
