package app.kamera.film

/**
 * Everything that changes the colour transform and therefore requires a new LUT.
 * All sliders are normalised to -1..1 with 0 as neutral unless stated otherwise.
 */
data class ColorGrade(
    /** Development in stops: -1 pull, 0 normal, up to +2 push. */
    val push: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val whites: Float = 0f,
    val blacks: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    /** Matte lift, 0..1. */
    val fade: Float = 0f,
    /** Split toning: hue in degrees on the Oklab circle, amount 0..1. */
    val shadowToneHue: Float = 240f,
    val shadowToneAmount: Float = 0f,
    val highlightToneHue: Float = 60f,
    val highlightToneAmount: Float = 0f,
    /** Colour mixer offsets per hue band, -1..1. */
    val mixerHue: List<Float> = ZERO_MIX,
    val mixerSaturation: List<Float> = ZERO_MIX,
    val mixerLightness: List<Float> = ZERO_MIX,
) {
    companion object {
        val ZERO_MIX: List<Float> = List(HueBands.COUNT) { 0f }
    }
}

/** Parameters applied per pixel in the shader; changing these never rebuilds the LUT. */
data class Effects(
    /** Exposure compensation in stops. */
    val exposure: Float = 0f,
    /** White balance offsets, -1..1. */
    val temperature: Float = 0f,
    val tint: Float = 0f,
    /** Multipliers over the stock's own values; 1 means "as manufactured". */
    val grainAmount: Float = 1f,
    val grainSize: Float = 1f,
    val halation: Float = 1f,
    val bloom: Float = 1f,
    val vignette: Float = 1f,
    /** 0..1 and -1..1. */
    val sharpen: Float = 0f,
    val clarity: Float = 0f,
)

/** Non-destructive framing of the negative. */
data class Framing(
    /** Output aspect (width / height) of the long-edge-horizontal format; 0 keeps the original. */
    val aspect: Float = 0f,
    /** Whole clockwise quarter turns. */
    val quarterTurns: Int = 0,
    /** Fine rotation in degrees, -45..45. */
    val straighten: Float = 0f,
    /** 1 = widest crop that fits. */
    val zoom: Float = 1f,
    /** Pan as a fraction of the available travel, -1..1. */
    val panX: Float = 0f,
    val panY: Float = 0f,
    val flipHorizontal: Boolean = false,
)

/** A complete, serialisable development recipe. Re-applying it to a negative is deterministic. */
data class Recipe(
    val stockId: String,
    val grade: ColorGrade = ColorGrade(),
    val effects: Effects = Effects(),
    val framing: Framing = Framing(),
) {
    /** Serialises to line-based `key=value` text; unknown keys are ignored when read back. */
    fun encode(): String = buildString {
        fun put(key: String, value: Any) = append(key).append('=').append(value).append('\n')
        fun putList(key: String, value: List<Float>) = put(key, value.joinToString(","))
        put("version", FORMAT_VERSION)
        put("stock", stockId)
        put("push", grade.push)
        put("contrast", grade.contrast)
        put("highlights", grade.highlights)
        put("shadows", grade.shadows)
        put("whites", grade.whites)
        put("blacks", grade.blacks)
        put("saturation", grade.saturation)
        put("vibrance", grade.vibrance)
        put("fade", grade.fade)
        put("shadowToneHue", grade.shadowToneHue)
        put("shadowToneAmount", grade.shadowToneAmount)
        put("highlightToneHue", grade.highlightToneHue)
        put("highlightToneAmount", grade.highlightToneAmount)
        putList("mixerHue", grade.mixerHue)
        putList("mixerSaturation", grade.mixerSaturation)
        putList("mixerLightness", grade.mixerLightness)
        put("exposure", effects.exposure)
        put("temperature", effects.temperature)
        put("tint", effects.tint)
        put("grainAmount", effects.grainAmount)
        put("grainSize", effects.grainSize)
        put("halation", effects.halation)
        put("bloom", effects.bloom)
        put("vignette", effects.vignette)
        put("sharpen", effects.sharpen)
        put("clarity", effects.clarity)
        put("aspect", framing.aspect)
        put("quarterTurns", framing.quarterTurns)
        put("straighten", framing.straighten)
        put("zoom", framing.zoom)
        put("panX", framing.panX)
        put("panY", framing.panY)
        put("flipHorizontal", framing.flipHorizontal)
    }

    companion object {
        const val FORMAT_VERSION = 1

        fun decode(text: String): Recipe? {
            val map = text.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
                }
                .toMap()
            val stockId = map["stock"] ?: return null
            fun f(key: String, default: Float) = map[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            fun list(key: String): List<Float> {
                val parts = map[key]?.split(",")?.mapNotNull { it.toFloatOrNull() } ?: return ColorGrade.ZERO_MIX
                return if (parts.size == HueBands.COUNT) parts else ColorGrade.ZERO_MIX
            }
            return Recipe(
                stockId = stockId,
                grade = ColorGrade(
                    push = f("push", 0f),
                    contrast = f("contrast", 0f),
                    highlights = f("highlights", 0f),
                    shadows = f("shadows", 0f),
                    whites = f("whites", 0f),
                    blacks = f("blacks", 0f),
                    saturation = f("saturation", 0f),
                    vibrance = f("vibrance", 0f),
                    fade = f("fade", 0f),
                    shadowToneHue = f("shadowToneHue", 240f),
                    shadowToneAmount = f("shadowToneAmount", 0f),
                    highlightToneHue = f("highlightToneHue", 60f),
                    highlightToneAmount = f("highlightToneAmount", 0f),
                    mixerHue = list("mixerHue"),
                    mixerSaturation = list("mixerSaturation"),
                    mixerLightness = list("mixerLightness"),
                ),
                effects = Effects(
                    exposure = f("exposure", 0f),
                    temperature = f("temperature", 0f),
                    tint = f("tint", 0f),
                    grainAmount = f("grainAmount", 1f),
                    grainSize = f("grainSize", 1f),
                    halation = f("halation", 1f),
                    bloom = f("bloom", 1f),
                    vignette = f("vignette", 1f),
                    sharpen = f("sharpen", 0f),
                    clarity = f("clarity", 0f),
                ),
                framing = Framing(
                    aspect = f("aspect", 0f),
                    quarterTurns = map["quarterTurns"]?.toIntOrNull() ?: 0,
                    straighten = f("straighten", 0f),
                    zoom = f("zoom", 1f),
                    panX = f("panX", 0f),
                    panY = f("panY", 0f),
                    flipHorizontal = map["flipHorizontal"]?.toBooleanStrictOrNull() ?: false,
                ),
            )
        }
    }
}
