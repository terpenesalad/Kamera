package app.kamera.film

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** How an emulsion turns exposure into a viewable picture. */
enum class FilmProcess { NEGATIVE_PRINT, REVERSAL, MONOCHROME }

/**
 * Physical description of a colour emulsion: what each layer sees, and the dyes it forms.
 *
 * Peaks and widths are in nanometres. The spectral shapes are smooth parametric fits to the general
 * form of published film data rather than digitised curves from any one manufacturer's datasheet.
 */
data class Chemistry(
    val process: FilmProcess,
    val redPeak: Float = 648f,
    val redWidth: Float = 27f,
    val greenPeak: Float = 548f,
    val greenWidth: Float = 29f,
    val bluePeak: Float = 445f,
    val blueWidth: Float = 26f,
    /** Scales the dyes' unwanted side absorptions: lower means purer, more saturated colour. */
    val dyeImpurity: Float = 1f,
    /** Fraction of the side absorption cancelled by coloured masking couplers (the orange base). */
    val masking: Float = 0.8f,
    /** Density gained per log10 of exposure on the film's straight-line section. */
    val filmGamma: Float = 0.62f,
    /** Density gained per log10 of exposure on the print paper. */
    val paperGamma: Float = 2.5f,
    /** Strength of the development-inhibitor couplers that keep each layer's colour pure. */
    val inhibitors: Float = 0.13f,
) {
    /** Linear Rec.709 scene light to relative exposure of each layer; rows sum to one. */
    val layerMatrix: FloatArray by lazy { Spectra.layerMatrix(this) }

    companion object {
        fun forKind(kind: FilmKind): Chemistry = when (kind) {
            FilmKind.COLOR_NEGATIVE, FilmKind.CINEMA -> Chemistry(FilmProcess.NEGATIVE_PRINT)
            FilmKind.DIGITAL -> Chemistry(FilmProcess.NEGATIVE_PRINT, masking = 0.9f)
            FilmKind.REVERSAL -> Chemistry(
                FilmProcess.REVERSAL,
                redPeak = 652f,
                redWidth = 25f,
                greenPeak = 545f,
                greenWidth = 26f,
                bluePeak = 442f,
                blueWidth = 24f,
                masking = 0f,
                filmGamma = 1.7f,
            )
            FilmKind.MONOCHROME -> Chemistry(FilmProcess.MONOCHROME, filmGamma = 0.68f)
        }
    }
}

/** Sampled spectra, 380 to 725 nm in 15 nm steps. */
internal object Spectra {
    const val COUNT = 24
    private val nm = FloatArray(COUNT) { 380f + 15f * it }

    /** Asymmetric Gaussian lobe. */
    fun lobe(peak: Float, left: Float, right: Float): FloatArray = FloatArray(COUNT) {
        val d = nm[it] - peak
        val s = if (d < 0f) left else right
        exp(-0.5f * d * d / (s * s))
    }

    private fun add(a: FloatArray, b: FloatArray, weight: Float) = FloatArray(COUNT) { a[it] + b[it] * weight }

    // CIE 1931 observer, multi-lobe fit of Wyman, Sloan and Shirley (JCGT 2013).
    private val cmfX = add(add(lobe(599.8f, 37.9f, 31.0f).scaled(1.056f), lobe(442.0f, 16.0f, 26.7f), 0.362f), lobe(501.1f, 20.4f, 26.2f), -0.065f)
    private val cmfY = add(lobe(568.8f, 46.9f, 40.5f).scaled(0.821f), lobe(530.9f, 16.3f, 31.1f), 0.286f)
    private val cmfZ = add(lobe(437.0f, 11.8f, 36.0f).scaled(1.217f), lobe(459.0f, 26.0f, 13.8f), 0.681f)

    private fun FloatArray.scaled(k: Float) = FloatArray(size) { this[it] * k }

    /** Relative power of a 6500 K radiator, a close stand-in for daylight viewing. */
    private val illuminant = FloatArray(COUNT) {
        val metres = nm[it] * 1e-9
        val value = 1.0 / (metres * metres * metres * metres * metres * (exp(1.4388e-2 / (metres * 6500.0)) - 1.0))
        (value / 1.0e31).toFloat()
    }

    val viewX = FloatArray(COUNT) { cmfX[it] * illuminant[it] }
    val viewY = FloatArray(COUNT) { cmfY[it] * illuminant[it] }
    val viewZ = FloatArray(COUNT) { cmfZ[it] * illuminant[it] }
    val whiteX = viewX.sum()
    val whiteY = viewY.sum()
    val whiteZ = viewZ.sum()

    /** Smooth reflectance bases used to turn an RGB value back into a plausible spectrum. */
    private val basisR = lobe(615f, 34f, 70f)
    private val basisG = lobe(535f, 34f, 38f)
    private val basisB = lobe(455f, 45f, 30f)

    fun layerMatrix(c: Chemistry): FloatArray {
        val bases = arrayOf(basisR, basisG, basisB)
        // XYZ of each basis, relative to the white point.
        val a = FloatArray(9)
        for (j in 0 until 3) {
            a[j * 3] = dot(viewX, bases[j]) / whiteX
            a[j * 3 + 1] = dot(viewY, bases[j]) / whiteY
            a[j * 3 + 2] = dot(viewZ, bases[j]) / whiteZ
        }
        // Weights of the bases that reproduce each Rec.709 primary (primaries in white-relative XYZ).
        val primaries = floatArrayOf(
            0.4339f, 0.2126f, 0.0177f,
            0.3762f, 0.7152f, 0.1095f,
            0.1899f, 0.0722f, 0.8728f,
        )
        val weights = multiply(primaries, invert(a))
        val layers = arrayOf(
            lobe(c.redPeak, c.redWidth, c.redWidth * 0.8f),
            lobe(c.greenPeak, c.greenWidth, c.greenWidth),
            lobe(c.bluePeak, c.blueWidth, c.blueWidth * 1.1f),
        )
        val out = FloatArray(9)
        for (i in 0 until 3) {
            for (p in 0 until 3) {
                var sum = 0f
                for (j in 0 until 3) sum += weights[p * 3 + j] * dot(layers[i], bases[j], illuminant)
                out[i * 3 + p] = sum
            }
            val total = out[i * 3] + out[i * 3 + 1] + out[i * 3 + 2]
            for (p in 0 until 3) out[i * 3 + p] /= total
        }
        return out
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until COUNT) s += a[i] * b[i]
        return s
    }

    private fun dot(a: FloatArray, b: FloatArray, c: FloatArray): Float {
        var s = 0f
        for (i in 0 until COUNT) s += a[i] * b[i] * c[i]
        return s
    }

    private fun multiply(a: FloatArray, b: FloatArray) = FloatArray(9) { i ->
        val r = i / 3
        val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    private fun invert(m: FloatArray): FloatArray {
        val c00 = m[4] * m[8] - m[5] * m[7]
        val c01 = m[5] * m[6] - m[3] * m[8]
        val c02 = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * c00 + m[1] * c01 + m[2] * c02
        return floatArrayOf(
            c00 / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
            c01 / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
            c02 / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
        )
    }
}

/** The neutral emulsion each stock is compared with, one per process. */
internal object ReferenceEmulsion {
    val negative: Reference by lazy { Reference(Chemistry.forKind(FilmKind.COLOR_NEGATIVE)) }
    val reversal: Reference by lazy { Reference(Chemistry.forKind(FilmKind.REVERSAL)) }

    class Reference(chemistry: Chemistry) {
        val chain = SpectralChain(chemistry)
        val matrix: FloatArray = chemistry.layerMatrix
        val inhibitors: Float = chemistry.inhibitors
    }

    fun forProcess(process: FilmProcess): Reference? = when (process) {
        FilmProcess.NEGATIVE_PRINT -> negative
        FilmProcess.REVERSAL -> reversal
        FilmProcess.MONOCHROME -> null
    }
}

/**
 * Simulates the dyes: film layers form cyan, magenta and yellow, light passes through them, and (for
 * negatives) exposes a print paper with its own dyes. The result is what a densitometer-true print
 * would show, including the colour shifts that come from imperfect dyes.
 *
 * Only the chromatic behaviour is used. [ratio] compares a colour with the neutral of the same
 * exposure, leaving the neutral tone scale to each stock's measured-by-eye characteristic curve.
 */
internal class SpectralChain(private val c: Chemistry) {
    private val negative = c.process == FilmProcess.NEGATIVE_PRINT
    private val filmSlope = c.filmGamma * LOG10_2
    private val paperSlope = c.paperGamma * LOG10_2

    // Film dyes, as natural-log absorbance per unit density.
    private val impurity = c.dyeImpurity * (1f - c.masking)
    private val filmCyan = dye(lobe(685f, 48f, 42f), lobe(530f, 40f, 40f), 0.14f * impurity, lobe(430f, 30f, 30f), 0.07f * impurity)
    private val filmMagenta = dye(lobe(548f, 30f, 34f), lobe(435f, 28f, 30f), 0.26f * impurity, lobe(640f, 30f, 30f), 0.03f * impurity)
    private val filmYellow = dye(lobe(447f, 26f, 30f), lobe(520f, 24f, 24f), 0.05f * impurity, null, 0f)

    // Print paper sensitivity and dyes.
    private val paperRed = Spectra.lobe(692f, 16f, 16f)
    private val paperGreen = Spectra.lobe(546f, 17f, 17f)
    private val paperBlue = Spectra.lobe(445f, 19f, 19f)
    private val printCyan = dye(lobe(665f, 50f, 44f), lobe(520f, 36f, 36f), 0.10f, null, 0f)
    private val printMagenta = dye(lobe(540f, 30f, 32f), lobe(430f, 26f, 26f), 0.14f, null, 0f)
    private val printYellow = dye(lobe(445f, 26f, 28f), null, 0f, null, 0f)

    private val printerGain = FloatArray(3) { 1f }

    /** Neutral response sampled from [NEUTRAL_MIN_STOPS] upward, one row per [NEUTRAL_STEP] stops. */
    private val neutral = FloatArray(NEUTRAL_ROWS * 3)

    init {
        if (negative) solvePrinterLights()
        val out = FloatArray(3)
        val transmission = FloatArray(Spectra.COUNT)
        for (row in 0 until NEUTRAL_ROWS) {
            val x = NEUTRAL_MIN_STOPS + row * NEUTRAL_STEP
            render(x, x, x, out, transmission)
            for (i in 0 until 3) neutral[row * 3 + i] = max(out[i], 1e-5f)
        }
    }

    private fun lobe(peak: Float, left: Float, right: Float) = Spectra.lobe(peak, left, right)

    private fun dye(main: FloatArray, second: FloatArray?, secondAmount: Float, third: FloatArray?, thirdAmount: Float) =
        FloatArray(Spectra.COUNT) {
            LN10 * (main[it] + (second?.get(it) ?: 0f) * secondAmount + (third?.get(it) ?: 0f) * thirdAmount)
        }

    /** Chooses printer lights so a middle-grey negative exposes every paper layer equally. */
    private fun solvePrinterLights() {
        val transmission = FloatArray(Spectra.COUNT)
        filmTransmission(0f, 0f, 0f, transmission)
        printerGain[0] = 1f / integrate(transmission, paperRed)
        printerGain[1] = 1f / integrate(transmission, paperGreen)
        printerGain[2] = 1f / integrate(transmission, paperBlue)
    }

    private fun integrate(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (k in 0 until Spectra.COUNT) sum += a[k] * b[k]
        return max(sum, 1e-12f)
    }

    private fun filmDensity(stops: Float): Float {
        val d = FilmMath.softplus((stops + 7f).toDouble(), 1.2) - FilmMath.softplus((stops - 8f).toDouble(), 1.6)
        return (d * filmSlope).toFloat()
    }

    private fun reversalDensity(stops: Float): Float {
        val d = FilmMath.softplus((3.2f - stops).toDouble(), 0.75) - FilmMath.softplus((-3.4f - stops).toDouble(), 0.75)
        return (d * filmSlope).toFloat()
    }

    private fun filmTransmission(xR: Float, xG: Float, xB: Float, out: FloatArray) {
        val cyan = if (negative) filmDensity(xR) else reversalDensity(xR)
        val magenta = if (negative) filmDensity(xG) else reversalDensity(xG)
        val yellow = if (negative) filmDensity(xB) else reversalDensity(xB)
        for (k in 0 until Spectra.COUNT) {
            out[k] = exp(-(cyan * filmCyan[k] + magenta * filmMagenta[k] + yellow * filmYellow[k]))
        }
    }

    private fun paperDensity(logExposure: Float): Float {
        val d = FilmMath.softplus((logExposure + 2.4f).toDouble(), 0.55) - FilmMath.softplus((logExposure - 2.0f).toDouble(), 0.7)
        return (d * paperSlope).toFloat()
    }

    /** Writes linear Rec.709 for layer exposures given in stops from middle grey. */
    private fun render(xR: Float, xG: Float, xB: Float, out: FloatArray, t: FloatArray) {
        filmTransmission(xR, xG, xB, t)
        if (negative) {
            val cyan = paperDensity(log2(integrate(t, paperRed) * printerGain[0]))
            val magenta = paperDensity(log2(integrate(t, paperGreen) * printerGain[1]))
            val yellow = paperDensity(log2(integrate(t, paperBlue) * printerGain[2]))
            for (k in 0 until Spectra.COUNT) {
                t[k] = exp(-(cyan * printCyan[k] + magenta * printMagenta[k] + yellow * printYellow[k]))
            }
        }
        var x = 0f
        var y = 0f
        var z = 0f
        for (k in 0 until Spectra.COUNT) {
            x += t[k] * Spectra.viewX[k]
            y += t[k] * Spectra.viewY[k]
            z += t[k] * Spectra.viewZ[k]
        }
        x /= Spectra.whiteX
        y /= Spectra.whiteY
        z /= Spectra.whiteZ
        // White-relative XYZ to linear Rec.709.
        out[0] = max(3.0799f * x - 1.5372f * y - 0.5427f * z, 0f)
        out[1] = max(-0.9212f * x + 1.8760f * y + 0.0452f * z, 0f)
        out[2] = max(0.0529f * x - 0.2040f * y + 1.1511f * z, 0f)
    }

    /**
     * Writes how far a colour departs from the neutral of the same exposure, per output channel.
     * [t] and [spectrum] are per-thread scratch; [greyStops] is the pixel's luminance in stops.
     */
    fun ratio(xR: Float, xG: Float, xB: Float, greyStops: Float, out: FloatArray, spectrum: FloatArray) {
        render(xR, xG, xB, out, spectrum)
        val position = ((greyStops - NEUTRAL_MIN_STOPS) / NEUTRAL_STEP).coerceIn(0f, NEUTRAL_ROWS - 1.001f)
        val row = position.toInt()
        val blend = position - row
        for (i in 0 until 3) {
            val reference = neutral[row * 3 + i] * (1f - blend) + neutral[(row + 1) * 3 + i] * blend
            out[i] = min(max(out[i] / reference, 1f / MAX_RATIO), MAX_RATIO)
        }
    }

    private fun log2(v: Float): Float = (ln(max(v, 1e-9f).toDouble()) / LN2).toFloat()

    private companion object {
        const val LN10 = 2.3025851f
        const val LN2 = 0.6931471805599453
        const val LOG10_2 = 0.30103f
        const val NEUTRAL_MIN_STOPS = -10f
        const val NEUTRAL_STEP = 0.25f
        const val NEUTRAL_ROWS = 73
        const val MAX_RATIO = 6f
    }
}
