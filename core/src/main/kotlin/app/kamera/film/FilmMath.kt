package app.kamera.film

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A simple linear RGB triple. Immutable; hot paths work on primitives instead. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    constructor(v: Float) : this(v, v, v)

    fun toArray(): FloatArray = floatArrayOf(r, g, b)
}

/**
 * The working encoding shared by the Kotlin colour model and `film.frag`.
 *
 * Every constant here has a twin in the shader. `ShaderParityTest` fails the build if they drift
 * apart, because a mismatch would break the promise that the viewfinder equals the final image.
 */
object FilmMath {
    /** Scene-linear value treated as photographic middle grey. */
    const val MID_GREY = 0.18f

    /** Lowest stop (relative to middle grey) representable by the LUT input. */
    const val LOG_MIN_STOPS = -8f

    /** Width of the LUT input range in stops. */
    const val LOG_RANGE_STOPS = 14f

    /** Strength of the inverse-Reinhard expansion that recovers highlights from display values. */
    const val HIGHLIGHT_EXPANSION = 0.90f

    fun clamp01(v: Float): Float = if (v < 0f) 0f else if (v > 1f) 1f else v

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = clamp01((x - e0) / (e1 - e0))
        return t * t * (3f - 2f * t)
    }

    fun srgbToLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f

    /** Display-referred sRGB code value to estimated scene-linear light. */
    fun displayToScene(display: Float): Float {
        val l = min(srgbToLinear(clamp01(display)), 1f)
        return (1f - MID_GREY * HIGHLIGHT_EXPANSION) * l / (1f - HIGHLIGHT_EXPANSION * l)
    }

    /** Scene-linear light to the LUT's log input in 0..1. */
    fun sceneToLog(scene: Float): Float {
        val stops = log2(max(scene, 1e-5f) / MID_GREY)
        return clamp01((stops - LOG_MIN_STOPS) / LOG_RANGE_STOPS)
    }

    /** Inverse of [sceneToLog]. */
    fun logToScene(encoded: Float): Float =
        MID_GREY * 2f.pow(encoded * LOG_RANGE_STOPS + LOG_MIN_STOPS)

    fun log2(v: Float): Float = (ln(v.toDouble()) / LN2).toFloat()

    /** Smooth, monotonic approximation of max(u, 0); `softness` sets the width of the knee. */
    fun softplus(u: Double, softness: Double): Double {
        val t = u / softness
        return when {
            t > 30.0 -> u
            t < -30.0 -> 0.0
            else -> softness * ln1p(exp(t))
        }
    }

    /** Rec.709 luminance of linear RGB. */
    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * White-balance gains that re-balance a scene from [baseKelvin] to [kelvin].
     *
     * Higher [kelvin] produces a warmer picture, matching the convention of camera white-balance
     * dials. Luminance is preserved so a temperature change never reads as an exposure change.
     */
    fun kelvinGains(kelvin: Float, baseKelvin: Float = 5500f): FloatArray {
        val target = kelvinToLinearRgb(kelvin)
        val base = kelvinToLinearRgb(baseKelvin)
        // A higher dial setting tells the camera the light is bluer, so it compensates towards warm.
        val r = base[0] / target[0]
        val g = base[1] / target[1]
        val b = base[2] / target[2]
        val norm = luma(r, g, b)
        return floatArrayOf(r / norm, g / norm, b / norm)
    }

    /** Linear sRGB colour of a Planckian radiator, normalised to unit green (Kim et al. fit). */
    fun kelvinToLinearRgb(kelvin: Float): FloatArray {
        val t = kelvin.coerceIn(1667f, 25000f).toDouble()
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t <= 4000.0) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t <= 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        val cx = x / y
        val cz = (1.0 - x - y) / y
        val r = 3.2404542 * cx - 1.5371385 - 0.4985314 * cz
        val g = -0.9692660 * cx + 1.8760108 + 0.0415560 * cz
        val b = 0.0556434 * cx - 0.2040259 + 1.0572252 * cz
        return floatArrayOf(max(r, 0.001).toFloat(), max(g, 0.001).toFloat(), max(b, 0.001).toFloat())
    }

    private const val LN2 = 0.6931471805599453
}

/** Linear sRGB ↔ Oklab, written against primitives so the LUT baker never allocates per node. */
internal object Oklab {
    fun lmsL(r: Float, g: Float, b: Float): Float = cb(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
    fun lmsM(r: Float, g: Float, b: Float): Float = cb(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
    fun lmsS(r: Float, g: Float, b: Float): Float = cb(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)

    private fun cb(v: Float): Float = cbrt(max(v, 0f).toDouble()).toFloat()

    fun toR(l: Float, a: Float, b: Float): Float {
        val p = l + 0.3963377774f * a + 0.2158037573f * b
        val q = l - 0.1055613458f * a - 0.0638541728f * b
        val s = l - 0.0894841775f * a - 1.2914855480f * b
        return 4.0767416621f * p * p * p - 3.3077115913f * q * q * q + 0.2309699292f * s * s * s
    }

    fun toG(l: Float, a: Float, b: Float): Float {
        val p = l + 0.3963377774f * a + 0.2158037573f * b
        val q = l - 0.1055613458f * a - 0.0638541728f * b
        val s = l - 0.0894841775f * a - 1.2914855480f * b
        return -1.2684380046f * p * p * p + 2.6097574011f * q * q * q - 0.3413193965f * s * s * s
    }

    fun toB(l: Float, a: Float, b: Float): Float {
        val p = l + 0.3963377774f * a + 0.2158037573f * b
        val q = l - 0.1055613458f * a - 0.0638541728f * b
        val s = l - 0.0894841775f * a - 1.2914855480f * b
        return -0.0041960863f * p * p * p - 0.7034186147f * q * q * q + 1.7076147010f * s * s * s
    }
}

/** Seven hue families used by film characters and by the editor's colour mixer. */
object HueBands {
    const val COUNT = 7
    val NAMES = listOf("Red", "Orange", "Yellow", "Green", "Aqua", "Blue", "Magenta")

    /** Band centres on the Oklab hue circle, in degrees. */
    private val CENTERS = floatArrayOf(25f, 60f, 105f, 145f, 195f, 262f, 330f)

    /** Raised-cosine membership of [hueDeg] in band [band]; memberships across bands sum to one. */
    fun weights(hueDeg: Float, out: FloatArray) {
        var total = 0f
        for (i in 0 until COUNT) {
            val d = abs(wrap180(hueDeg - CENTERS[i]))
            val w = if (d >= WIDTH) 0f else 0.5f + 0.5f * cos(Math.PI.toFloat() * d / WIDTH)
            out[i] = w
            total += w
        }
        if (total > 0f) for (i in 0 until COUNT) out[i] /= total
    }

    private fun wrap180(deg: Float): Float {
        var d = deg % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private const val WIDTH = 72f
}

/** Small polar helpers used by the colour model. */
internal object Polar {
    fun hueDeg(a: Float, b: Float): Float {
        val h = Math.toDegrees(atan2(b.toDouble(), a.toDouble())).toFloat()
        return if (h < 0f) h + 360f else h
    }

    fun chroma(a: Float, b: Float): Float = sqrt(a * a + b * b)

    fun cosDeg(deg: Float): Float = cos(Math.toRadians(deg.toDouble())).toFloat()

    fun sinDeg(deg: Float): Float = sin(Math.toRadians(deg.toDouble())).toFloat()
}
