package app.kamera.film

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * A film characteristic curve in the log-log domain: scene stops in, output stops out.
 *
 * The curve is a straight line with soft knees at both ends, parameterised so that it passes exactly
 * through middle grey and approaches exactly the requested black and white levels. It is strictly
 * monotonic, so tone never inverts regardless of how far the editor's sliders are pushed.
 */
internal class ToneCurve(
    private val slope: Double,
    private val toe: Double,
    private val toeSoft: Double,
    private val shoulder: Double,
    private val shoulderSoft: Double,
    private val floor: Double,
) {
    fun apply(stops: Double): Double =
        slope * (FilmMath.softplus(stops - toe, toeSoft) - FilmMath.softplus(stops - shoulder, shoulderSoft)) + floor

    companion object {
        /**
         * @param bottom asymptotic output in stops below mid (negative)
         * @param top asymptotic output in stops above mid (positive)
         */
        fun solve(slope: Double, bottom: Double, top: Double, toeSoft: Double, shoulderSoft: Double): ToneCurve {
            val span = (top - bottom) / slope
            var lo = -40.0
            var hi = 0.0
            repeat(48) {
                val toe = 0.5 * (lo + hi)
                val shoulder = toe + span
                val atMid = slope * (FilmMath.softplus(-toe, toeSoft) - FilmMath.softplus(-shoulder, shoulderSoft)) + bottom
                if (atMid > 0.0) lo = toe else hi = toe
            }
            val toe = 0.5 * (lo + hi)
            return ToneCurve(slope, toe, toeSoft, toe + span, shoulderSoft, bottom)
        }
    }
}

/**
 * The compiled colour response of one stock under one grade.
 *
 * Input is log-encoded scene light; output is display-referred sRGB. Instances are immutable and
 * safe to evaluate from several threads, which is how [LutBaker] fills a cube in parallel.
 */
class FilmResponse(stock: FilmStock, private val grade: ColorGrade) {
    private val mono = stock.isMonochrome
    private val passthrough = stock.id == FilmCatalog.NEUTRAL_ID
    private val passContrast = 2f.pow(grade.contrast.coerceIn(-1f, 1f) * 0.55f)
    private val passFade = grade.fade.coerceIn(0f, 1f) * 0.07f
    private val sens = if (stock.isMonochrome) stock.sensitivity else stock.chemistry.layerMatrix
    private val spectral = if (stock.isMonochrome || stock.id == FilmCatalog.NEUTRAL_ID) null else SpectralChain(stock.chemistry)
    private val reference = if (spectral == null) null else ReferenceEmulsion.forProcess(stock.chemistry.process)
    private val chromaGain = 1f + 0.14f * grade.push.coerceIn(-1f, 2f)
    private val inter = FloatArray(9) { i ->
        val k = if (stock.isMonochrome) 0f else stock.chemistry.inhibitors
        stock.interlayer[i] + if (i % 4 == 0) 2f * k else -k
    }

    private val shadowSat = stock.shadowSaturation
    private val highlightSat = stock.highlightSaturation
    private val baseSat = stock.saturation

    private val midR = stock.mid.r
    private val midG = stock.mid.g
    private val midB = stock.mid.b

    private val curveR: ToneCurve
    private val curveG: ToneCurve
    private val curveB: ToneCurve
    private val topStops: Float
    private val bottomStops: Float

    private val push = grade.push.coerceIn(-1f, 2f)

    private val hueShift = FloatArray(HueBands.COUNT) {
        stock.hueShift[it] + grade.mixerHue[it] * 30f
    }
    private val bandSat = FloatArray(HueBands.COUNT) {
        stock.bandSaturation[it] * 2f.pow(grade.mixerSaturation[it] * 1.2f)
    }
    private val bandLum = FloatArray(HueBands.COUNT) { stock.bandLightness[it] + grade.mixerLightness[it] * 0.12f }

    private val saturationGain = 2f.pow(grade.saturation.coerceIn(-1f, 1f) * if (grade.saturation < 0f) 4f else 1.1f)
    private val vibrance = grade.vibrance.coerceIn(-1f, 1f)

    private val shadowA = stock.shadowTint[0] + grade.shadowToneAmount * 0.05f * Polar.cosDeg(grade.shadowToneHue)
    private val shadowB = stock.shadowTint[1] + grade.shadowToneAmount * 0.05f * Polar.sinDeg(grade.shadowToneHue)
    private val highlightA =
        stock.highlightTint[0] + grade.highlightToneAmount * 0.05f * Polar.cosDeg(grade.highlightToneHue)
    private val highlightB =
        stock.highlightTint[1] + grade.highlightToneAmount * 0.05f * Polar.sinDeg(grade.highlightToneHue)

    private val highlightsAmount = grade.highlights.coerceIn(-1f, 1f)
    private val shadowsAmount = grade.shadows.coerceIn(-1f, 1f)

    init {
        val contrastGain = 2.0.pow(grade.contrast.coerceIn(-1f, 1f) * 0.55) * (1.0 + 0.14 * push)
        val blackGain = 2.0.pow(-grade.blacks.coerceIn(-1f, 1f) * 1.4 - 0.45 * max(push, 0f))
        val fadeLift = grade.fade.coerceIn(0f, 1f) * 0.07f
        val whiteGain = 2.0.pow(grade.whites.coerceIn(-1f, 1f) * 0.45)

        fun curve(contrast: Float, mid: Float, black: Float, white: Float): ToneCurve {
            val top = FilmMath.log2((min(white * whiteGain.toFloat(), 1f)) / mid).toDouble()
            val floor = max(black * blackGain.toFloat() + fadeLift, 1e-4f)
            val bottom = FilmMath.log2(floor / mid).toDouble()
            return ToneCurve.solve(
                slope = (contrast * contrastGain).coerceAtLeast(0.2),
                bottom = bottom,
                top = max(top, 0.05),
                toeSoft = stock.toeSoftness.toDouble(),
                shoulderSoft = stock.shoulderSoftness.toDouble(),
            )
        }
        curveR = curve(stock.contrast.r, midR, stock.black.r, stock.white.r)
        curveG = curve(stock.contrast.g, midG, stock.black.g, stock.white.g)
        curveB = curve(stock.contrast.b, midB, stock.black.b, stock.white.b)
        topStops = FilmMath.log2(min(stock.white.g * whiteGain.toFloat(), 1f) / midG)
        bottomStops = FilmMath.log2(max(stock.black.g * blackGain.toFloat() + fadeLift, 1e-4f) / midG)
    }

    /**
     * Evaluates the response for log-encoded input and writes sRGB code values to [out].
     * [scratch] must hold at least [SCRATCH_SIZE] floats; one per thread.
     */
    fun evaluate(logR: Float, logG: Float, logB: Float, out: FloatArray, scratch: FloatArray) {
        val sceneR = FilmMath.logToScene(logR)
        val sceneG = FilmMath.logToScene(logG)
        val sceneB = FilmMath.logToScene(logB)

        // 1. Spectral sensitivity of each emulsion layer.
        val expR = max(sens[0] * sceneR + sens[1] * sceneG + sens[2] * sceneB, 1e-6f)
        val expG = max(sens[3] * sceneR + sens[4] * sceneG + sens[5] * sceneB, 1e-6f)
        val expB = max(sens[6] * sceneR + sens[7] * sceneG + sens[8] * sceneB, 1e-6f)

        val xR = FilmMath.log2(expR / FilmMath.MID_GREY)
        val xG = FilmMath.log2(expG / FilmMath.MID_GREY)
        val xB = FilmMath.log2(expB / FilmMath.MID_GREY)

        // 2. Interlayer effects: each developing layer restrains its neighbours.
        val yR = inter[0] * xR + inter[1] * xG + inter[2] * xB
        val yG = inter[3] * xR + inter[4] * xG + inter[5] * xB
        val yB = inter[6] * xR + inter[7] * xG + inter[8] * xB

        // 3. The stock's tone scale, driven by the pixel's luminance, then tonal grading.
        val greyStops = if (spectral == null) yG else {
            val shown = FilmMath.luma(displayLinear(sceneR), displayLinear(sceneG), displayLinear(sceneB))
            val a = FilmMath.HIGHLIGHT_EXPANSION
            val scene = (1f - FilmMath.MID_GREY * a) * shown / max(1f - a * shown, 1e-3f)
            FilmMath.log2(max(scene, 1e-6f) / FilmMath.MID_GREY)
        }
        val tR = shapeTone(curveR.apply((if (spectral == null) yR else greyStops).toDouble()).toFloat())
        val tG = shapeTone(curveG.apply(greyStops.toDouble()).toFloat())
        val tB = shapeTone(curveB.apply((if (spectral == null) yB else greyStops).toDouble()).toFloat())

        var r = midR * pow2(tR)
        var g = midG * pow2(tG)
        var b = midB * pow2(tB)
        if (spectral != null && reference != null) {
            // 3b. Start from the scene's own colour, then apply what this emulsion's dyes do
            // differently from a neutral reference emulsion at the same exposure.
            val shownR = displayLinear(sceneR)
            val shownG = displayLinear(sceneG)
            val shownB = displayLinear(sceneB)
            val shownLuma = max(FilmMath.luma(shownR, shownG, shownB), 1e-6f)
            spectral.ratio(yR, yG, yB, greyStops, out, scratch)
            val stockR = out[0]
            val stockG = out[1]
            val stockB = out[2]
            val m = reference.matrix
            val k = reference.inhibitors
            val eR = FilmMath.log2(max(m[0] * sceneR + m[1] * sceneG + m[2] * sceneB, 1e-6f) / FilmMath.MID_GREY)
            val eG = FilmMath.log2(max(m[3] * sceneR + m[4] * sceneG + m[5] * sceneB, 1e-6f) / FilmMath.MID_GREY)
            val eB = FilmMath.log2(max(m[6] * sceneR + m[7] * sceneG + m[8] * sceneB, 1e-6f) / FilmMath.MID_GREY)
            reference.chain.ratio(
                eR + k * (2f * eR - eG - eB),
                eG + k * (2f * eG - eR - eB),
                eB + k * (2f * eB - eR - eG),
                greyStops,
                out,
                scratch,
            )
            r *= (shownR / shownLuma).coerceIn(0.02f, 12f) * gained(stockR / out[0])
            g *= (shownG / shownLuma).coerceIn(0.02f, 12f) * gained(stockG / out[1])
            b *= (shownB / shownLuma).coerceIn(0.02f, 12f) * gained(stockB / out[2])
        }
        if (passthrough) {
            r = passthroughTone(sceneR)
            g = passthroughTone(sceneG)
            b = passthroughTone(sceneB)
        }

        // 4. Dye character, handled in Oklab so hue moves do not disturb lightness.
        val lmsL = Oklab.lmsL(r, g, b)
        val lmsM = Oklab.lmsM(r, g, b)
        val lmsS = Oklab.lmsS(r, g, b)
        var labL = 0.2104542553f * lmsL + 0.7936177850f * lmsM - 0.0040720468f * lmsS
        var labA = 1.9779984951f * lmsL - 2.4285922050f * lmsM + 0.4505937099f * lmsS
        var labB = 0.0259040371f * lmsL + 0.7827717662f * lmsM - 0.8086757660f * lmsS

        if (mono) {
            labA = 0f
            labB = 0f
        } else {
            val hue = Polar.hueDeg(labA, labB)
            var chroma = Polar.chroma(labA, labB)
            HueBands.weights(hue, scratch)
            var hueDelta = 0f
            var satGain = 0f
            var lumDelta = 0f
            for (i in 0 until HueBands.COUNT) {
                hueDelta += scratch[i] * hueShift[i]
                satGain += scratch[i] * bandSat[i]
                lumDelta += scratch[i] * bandLum[i]
            }
            val presence = FilmMath.smoothstep(0.015f, 0.09f, chroma)
            labL += lumDelta * presence * labL
            chroma *= FilmMath.lerp(1f, satGain, presence) * baseSat * saturationGain

            if (vibrance != 0f) {
                val headroom = 1f - FilmMath.smoothstep(0.02f, 0.22f, chroma)
                chroma *= 1f + vibrance * 0.9f * headroom
            }

            val shadowFade = 1f - FilmMath.smoothstep(0.08f, 0.42f, labL)
            val highlightFade = FilmMath.smoothstep(0.72f, 0.98f, labL)
            chroma *= FilmMath.lerp(1f, shadowSat, shadowFade) * FilmMath.lerp(1f, highlightSat, highlightFade)

            val h = hue + hueDelta * presence
            labA = chroma * Polar.cosDeg(h)
            labB = chroma * Polar.sinDeg(h)
        }

        // 5. Split toning, which also colours monochrome stocks.
        val shadowWeight = (1f - FilmMath.clamp01(labL)).let { it * it }
        val highlightWeight = FilmMath.clamp01(labL).let { it * it }
        labA += shadowA * shadowWeight + highlightA * highlightWeight
        labB += shadowB * shadowWeight + highlightB * highlightWeight

        labL = FilmMath.clamp01(labL)
        writeInGamut(labL, labA, labB, out)
    }

    private fun gained(ratio: Float): Float = if (chromaGain == 1f) ratio else ratio.toDouble().pow(chromaGain.toDouble()).toFloat()

    /** Undoes the input decode, recovering the light the source picture actually displayed. */
    private fun displayLinear(scene: Float): Float {
        val a = FilmMath.HIGHLIGHT_EXPANSION
        return scene / ((1f - FilmMath.MID_GREY * a) + a * scene)
    }

    /** Returns the source for "No film", then applies the tonal sliders. */
    private fun passthroughTone(scene: Float): Float {
        val display = displayLinear(scene)
        val stops = FilmMath.log2(max(display, 1e-5f) / FilmMath.MID_GREY) * passContrast
        val toned = FilmMath.MID_GREY * pow2(shapeTone(stops))
        return passFade + min(toned, 1f) * (1f - passFade)
    }

    /**
     * Applies the Highlights and Shadows sliders as bumps in the log domain. Each bump's height is
     * limited by its width, which keeps the slope positive so the tonal scale can never invert.
     */
    private fun shapeTone(y: Float): Float {
        var t = y
        if (highlightsAmount != 0f) {
            val riseWidth = topStops * 0.55f + 0.8f
            val fallWidth = max(topStops * 0.6f, 0.2f)
            val height = min(0.75f, 0.42f * min(riseWidth / 1.5f, fallWidth))
            val rise = FilmMath.smoothstep(-0.8f, topStops * 0.55f, t)
            val fall = FilmMath.clamp01((topStops - t) / fallWidth)
            t += highlightsAmount * height * rise * fall
        }
        if (shadowsAmount != 0f) {
            val riseWidth = max(-bottomStops * 0.55f, 0.1f)
            val fallWidth = max(0.5f - bottomStops * 0.35f, 0.1f)
            val height = min(1.1f, 0.42f * min(riseWidth, fallWidth) / 1.5f)
            val rise = FilmMath.smoothstep(bottomStops, bottomStops * 0.45f, t)
            val fall = 1f - FilmMath.smoothstep(bottomStops * 0.35f, 0.5f, t)
            t += shadowsAmount * height * rise * fall
        }
        return t
    }

    /** Converts Oklab to sRGB, pulling chroma toward neutral until the colour fits the gamut. */
    private fun writeInGamut(lightness: Float, a: Float, b: Float, out: FloatArray) {
        var l = lightness
        var r = Oklab.toR(l, a, b)
        var g = Oklab.toG(l, a, b)
        var bl = Oklab.toB(l, a, b)
        if (!inGamut(r, g, bl)) {
            // Dense dyes keep their colour by getting darker, so give up some lightness first.
            l -= 0.09f * FilmMath.smoothstep(0.62f, 0.95f, l) * FilmMath.smoothstep(0.03f, 0.12f, Polar.chroma(a, b))
            var lo = 0f
            var hi = 1f
            repeat(8) {
                val mid = 0.5f * (lo + hi)
                val tr = Oklab.toR(l, a * mid, b * mid)
                val tg = Oklab.toG(l, a * mid, b * mid)
                val tb = Oklab.toB(l, a * mid, b * mid)
                if (inGamut(tr, tg, tb)) lo = mid else hi = mid
            }
            r = Oklab.toR(l, a * lo, b * lo)
            g = Oklab.toG(l, a * lo, b * lo)
            bl = Oklab.toB(l, a * lo, b * lo)
        }
        out[0] = FilmMath.linearToSrgb(FilmMath.clamp01(r))
        out[1] = FilmMath.linearToSrgb(FilmMath.clamp01(g))
        out[2] = FilmMath.linearToSrgb(FilmMath.clamp01(bl))
    }

    private fun inGamut(r: Float, g: Float, b: Float): Boolean =
        r >= -GAMUT_EPS && r <= 1f + GAMUT_EPS && g >= -GAMUT_EPS && g <= 1f + GAMUT_EPS &&
            b >= -GAMUT_EPS && b <= 1f + GAMUT_EPS

    private fun pow2(v: Float): Float = if (abs(v) < 1e-5f) 1f else 2f.pow(v)

    companion object {
        /** Scratch floats [evaluate] needs per thread. */
        const val SCRATCH_SIZE = 32
        private const val GAMUT_EPS = 1e-4f
    }
}
