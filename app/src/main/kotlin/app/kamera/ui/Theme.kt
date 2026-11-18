package app.kamera.ui

import android.app.Activity
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
    val background = Color.rgb(0, 0, 0)
    val surface = Color.rgb(13, 13, 14)
    val raised = Color.rgb(26, 26, 28)
    val line = Color.rgb(42, 42, 45)
    val text = Color.rgb(196, 192, 183)
    val muted = Color.rgb(122, 120, 113)
    val faint = Color.rgb(78, 76, 72)
    val signal = Color.rgb(214, 82, 38)
    val amber = Color.rgb(206, 154, 56)
    val record = Color.rgb(206, 40, 32)
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

/** A small dark message over the current screen, replacing the system's theme-following toast. */
fun Activity.notice(message: String, long: Boolean = false) {
    val host = findViewById<ViewGroup>(android.R.id.content) ?: return
    val pill = text(message, 13f, Palette.text, Type.body, Gravity.CENTER).apply {
        background = rounded(Palette.raised, dpf(18), Palette.line, dp(1))
        setPadding(dp(16), dp(10), dp(16), dp(10))
    }
    val params = frameParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(150) }
    host.addView(pill, params)
    pill.postDelayed({ host.removeView(pill) }, if (long) 3500L else 1800L)
}
