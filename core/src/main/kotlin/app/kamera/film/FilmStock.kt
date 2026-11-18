package app.kamera.film

/** Broad family a stock belongs to; drives grouping in the film picker. */
enum class FilmKind(val label: String) {
    COLOR_NEGATIVE("Colour negative"),
    CINEMA("Motion picture"),
    REVERSAL("Slide"),
    MONOCHROME("Black & white"),
}

/**
 * Grain as a physical property of the emulsion.
 *
 * @property rms     Density fluctuation in stops, measured at the film plane.
 * @property sizeUm  Correlation length of the grain clumps in micrometres on a 24 mm tall frame.
 * @property chroma  0 = luminance-only grain, 1 = fully independent dye clouds per layer.
 * @property shadow  Extra weight in the shadows (fast and pushed films get rougher there).
 */
data class GrainSpec(val rms: Float, val sizeUm: Float, val chroma: Float, val shadow: Float = 0.25f)

/**
 * Light scattered back through the emulsion from the film base.
 *
 * @property strength Amount of halo added around highlights.
 * @property radius   Halo radius as a fraction of frame height.
 * @property tint     Colour of the halo; red-orange on real film because only the red layer sees it.
 */
data class HalationSpec(val strength: Float, val radius: Float, val tint: Rgb)

/** Wide, neutral glow from lens and emulsion diffusion. */
data class BloomSpec(val strength: Float, val radius: Float)

/**
 * Complete description of a simulated emulsion.
 *
 * The stocks are original, hand-tuned interpretations. [reference] names the class of film each one
 * draws on so a photographer knows what to expect; none of them claims to be a measured profile.
 */
class FilmStock(
    val id: String,
    val name: String,
    val iso: Int,
    val kind: FilmKind,
    val character: String,
    val reference: String,
    /** Colours of the box art in the film picker: main and stripe, as 0xRRGGBB. */
    val boxColor: Int,
    val stripeColor: Int,
    /** Daylight (5500) or tungsten (3200) balance; used by the camera's "Film" white balance. */
    val balanceKelvin: Int = 5500,

    /** Spectral response of the three emulsion layers, as a row-major 3×3 over linear Rec.709. */
    val sensitivity: FloatArray = IDENTITY,
    /** Interlayer (DIR coupler) effects between layers in the log domain, row-major 3×3. */
    val interlayer: FloatArray = IDENTITY,

    /** Slope of the straight-line section, per channel, in output stops per scene stop. */
    val contrast: Rgb = Rgb(1f),
    /** Display-linear output for scene middle grey. Unequal channels give the stock its cast. */
    val mid: Rgb = Rgb(0.18f),
    /** Display-linear density floor. Lifted blacks are the matte base of a print. */
    val black: Rgb = Rgb(0.008f),
    /** Display-linear paper white. */
    val white: Rgb = Rgb(0.96f),
    val toeSoftness: Float = 1.2f,
    val shoulderSoftness: Float = 1.4f,

    val saturation: Float = 1f,
    /** Chroma multiplier reached in the deepest shadows and brightest highlights. */
    val shadowSaturation: Float = 0.85f,
    val highlightSaturation: Float = 0.7f,

    /** Per-hue-band adjustments, ordered as [HueBands.NAMES]. */
    val hueShift: FloatArray = ZERO_BANDS,
    val bandSaturation: FloatArray = UNIT_BANDS,
    val bandLightness: FloatArray = ZERO_BANDS,

    /** Oklab a/b tint added in the shadows and highlights. */
    val shadowTint: FloatArray = ZERO_AB,
    val highlightTint: FloatArray = ZERO_AB,

    val grain: GrainSpec,
    val halation: HalationSpec,
    val bloom: BloomSpec,
    val vignette: Float = 0.08f,
) {
    val isMonochrome: Boolean get() = kind == FilmKind.MONOCHROME

    companion object {
        val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val ZERO_BANDS = FloatArray(HueBands.COUNT)
        val UNIT_BANDS = FloatArray(HueBands.COUNT) { 1f }
        val ZERO_AB = floatArrayOf(0f, 0f)

        /** Symmetric matrix whose rows sum to one, so neutral light stays neutral. */
        fun crosstalk(amount: Float): FloatArray = floatArrayOf(
            1f + 2f * amount, -amount, -amount,
            -amount, 1f + 2f * amount, -amount,
            -amount, -amount, 1f + 2f * amount,
        )

        /** Panchromatic response for monochrome stocks: the same weights fill every row. */
        fun panchromatic(r: Float, g: Float, b: Float): FloatArray {
            val sum = r + g + b
            val (nr, ng, nb) = Triple(r / sum, g / sum, b / sum)
            return floatArrayOf(nr, ng, nb, nr, ng, nb, nr, ng, nb)
        }

        fun bands(
            red: Float = 0f,
            orange: Float = 0f,
            yellow: Float = 0f,
            green: Float = 0f,
            aqua: Float = 0f,
            blue: Float = 0f,
            magenta: Float = 0f,
        ) = floatArrayOf(red, orange, yellow, green, aqua, blue, magenta)

        fun unitBands(
            red: Float = 1f,
            orange: Float = 1f,
            yellow: Float = 1f,
            green: Float = 1f,
            aqua: Float = 1f,
            blue: Float = 1f,
            magenta: Float = 1f,
        ) = floatArrayOf(red, orange, yellow, green, aqua, blue, magenta)
    }
}
