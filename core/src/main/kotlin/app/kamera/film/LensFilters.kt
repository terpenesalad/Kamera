package app.kamera.film

/** The three slots of a square filter holder in front of the lens. */
enum class FilterSlot(val label: String) {
    COLOUR("Colour"),
    GRAD("Graduated"),
    EFFECT("Effect"),
}

/**
 * A square glass or resin filter in the style of the Cokin system.
 *
 * Filters act on light before it reaches the film, so a red filter on black-and-white film darkens
 * skies the way it would on a real camera, and grads and diffusers shape halation and grain too.
 */
class LensFilter(
    val id: String,
    val name: String,
    val slot: FilterSlot,
    val reference: String,
    /** Light passed per channel at full strength; for a grad, at its dense edge. */
    val transmission: Rgb = Rgb(1f),
    /** Default width of a grad's transition, 0 hard to 1 very soft. */
    val edge: Float = 0.3f,
    val polariser: Boolean = false,
    /** Fraction of light a diffuser scatters into a soft halo. */
    val diffusion: Float = 0f,
    /** Size of that halo as a fraction of the frame's short edge. */
    val diffusionRadius: Float = 0f,
    /** Keeps the centre clear, like a centre-spot filter. */
    val centreSpot: Boolean = false,
    /** Lowers saturation and lifts tones for a pastel look. */
    val pastel: Float = 0f,
    val starPoints: Int = 0,
    /** How quickly star rays fade, as a fraction of the frame's short edge. */
    val starLength: Float = 0f,
)

object FilterCatalog {
    /** Colour of daylight after a filter with this mired shift. Positive shifts warm the picture. */
    private fun mired(shift: Float): Rgb {
        val base = 5500f
        val shifted = FilmMath.kelvinToLinearRgb(1e6f / (1e6f / base + shift))
        val daylight = FilmMath.kelvinToLinearRgb(base)
        return Rgb(shifted[0] / daylight[0], shifted[1] / daylight[1], shifted[2] / daylight[2])
    }

    private fun colour(id: String, name: String, reference: String, t: Rgb) =
        LensFilter(id, name, FilterSlot.COLOUR, reference, transmission = t)

    private fun grad(id: String, name: String, reference: String, t: Rgb, edge: Float = 0.3f) =
        LensFilter(id, name, FilterSlot.GRAD, reference, transmission = t, edge = edge)

    val all: List<LensFilter> = listOf(
        colour("w81a", "81A", "Light warming, 81A", mired(18f)),
        colour("w81b", "81B", "Warming, 81B", mired(27f)),
        colour("w81c", "81C", "Warming, after Cokin 028 (81C)", mired(35f)),
        colour("w81ef", "81EF", "Strong warming, after Cokin 037 (81EF)", mired(53f)),
        colour("a85b", "85B Amber", "Daylight to tungsten film, 85B", mired(131f)),
        colour("c82a", "82A", "Light cooling, 82A", mired(-21f)),
        colour("c82b", "82B", "Cooling, 82B", mired(-32f)),
        colour("b80a", "80A Blue", "Tungsten to daylight, after Cokin 020 (80A)", mired(-131f)),
        colour("sepia", "Sepia", "Warm brown toning filter", Rgb(1.0f, 0.74f, 0.42f)),
        colour("yellow", "Yellow", "Black-and-white contrast, after Cokin 001", Rgb(1.0f, 0.93f, 0.18f)),
        colour("orange", "Orange", "Black-and-white contrast, after Cokin 002", Rgb(1.0f, 0.58f, 0.05f)),
        colour("red", "Red", "Black-and-white contrast, after Cokin 003", Rgb(0.95f, 0.10f, 0.02f)),
        colour("green", "Green", "Black-and-white contrast for foliage and skin", Rgb(0.34f, 0.90f, 0.30f)),
        LensFilter("polariser", "Polariser", FilterSlot.COLOUR, "Circular polariser: deeper skies, less haze", polariser = true),

        grad("g1", "Grey ND2", "1-stop grey grad, after Cokin 121L", Rgb(0.5f)),
        grad("g2m", "Grey ND4", "2-stop grey grad, after Cokin 121M", Rgb(0.25f)),
        grad("g2", "Grey ND8", "3-stop grey grad, after Cokin 121", Rgb(0.125f), edge = 0.2f),
        grad("g2s", "Grey ND8 Soft", "3-stop soft grey grad, after Cokin 121S", Rgb(0.125f), edge = 0.55f),
        grad("tobacco1", "Tobacco T1", "Light tobacco grad, after Cokin 124", Rgb(0.70f, 0.58f, 0.45f)),
        grad("tobacco2", "Tobacco T2", "Tobacco grad, after Cokin 125", Rgb(0.46f, 0.30f, 0.17f)),
        grad("blue1", "Blue B1", "Light blue grad, after Cokin 122", Rgb(0.55f, 0.68f, 0.92f)),
        grad("blue2", "Blue B2", "Blue grad, after Cokin 123", Rgb(0.28f, 0.42f, 0.82f)),
        grad("sunset1", "Sunset 1", "Orange-red grad, after Cokin 197", Rgb(0.95f, 0.55f, 0.30f), edge = 0.45f),
        grad("sunset2", "Sunset 2", "Deep sunset grad, after Cokin 198", Rgb(0.90f, 0.38f, 0.17f), edge = 0.45f),
        grad("mauve", "Mauve M2", "Mauve grad, after Cokin 127", Rgb(0.62f, 0.38f, 0.62f)),
        grad("emerald", "Emerald E2", "Green grad, after Cokin 131", Rgb(0.32f, 0.62f, 0.45f)),

        LensFilter("diffuser1", "Diffuser 1", FilterSlot.EFFECT, "Gentle softening, after Cokin 820 Diffuser Light", diffusion = 0.22f, diffusionRadius = 0.010f),
        LensFilter("diffuser2", "Diffuser 2", FilterSlot.EFFECT, "Stronger dreamy softening", diffusion = 0.40f, diffusionRadius = 0.013f),
        LensFilter("mist", "Mist", FilterSlot.EFFECT, "Glowing highlights with gentler contrast", diffusion = 0.20f, diffusionRadius = 0.028f),
        LensFilter("fog1", "Fog 1", FilterSlot.EFFECT, "Light fog: lifted shadows and a soft veil", diffusion = 0.36f, diffusionRadius = 0.036f),
        LensFilter("fog2", "Fog 2", FilterSlot.EFFECT, "Dense fog", diffusion = 0.56f, diffusionRadius = 0.037f),
        LensFilter("spot", "Centre Spot", FilterSlot.EFFECT, "Clear centre, softened edges, after Cokin 062", diffusion = 0.62f, diffusionRadius = 0.020f, centreSpot = true),
        LensFilter("pastel", "Pastel", FilterSlot.EFFECT, "Soft, light, pastel colour", diffusion = 0.30f, diffusionRadius = 0.020f, pastel = 0.35f),
        LensFilter("star4", "Star 4", FilterSlot.EFFECT, "Four-point stars on lights, after Cokin 057", starPoints = 4, starLength = 0.024f),
        LensFilter("star6", "Star 6", FilterSlot.EFFECT, "Six-point stars on lights", starPoints = 6, starLength = 0.022f),
        LensFilter("star8", "Star 8", FilterSlot.EFFECT, "Eight-point stars on lights, after Cokin 056", starPoints = 8, starLength = 0.021f),
    )

    private val byId = all.associateBy { it.id }

    fun find(id: String?): LensFilter? = id?.let(byId::get)

    fun forSlot(slot: FilterSlot): List<LensFilter> = all.filter { it.slot == slot }
}

/** What is loaded in the holder. Strength 1 is the filter as made. */
data class LensFilters(
    val colorId: String? = null,
    val colorStrength: Float = 1f,
    val gradId: String? = null,
    val gradStrength: Float = 1f,
    /** Where the grad's transition sits, -1 at the bottom of the frame to 1 at the top. */
    val gradPosition: Float = 0f,
    val gradSoftness: Float = 0.3f,
    /** Rotation of the grad in degrees, for sloping horizons. */
    val gradTilt: Float = 0f,
    val effectId: String? = null,
    val effectStrength: Float = 1f,
) {
    val isEmpty: Boolean get() = colorId == null && gradId == null && effectId == null

    val names: List<String> get() = listOfNotNull(colorId, gradId, effectId).mapNotNull { FilterCatalog.find(it)?.name }

    fun encode(): String = buildString {
        fun put(key: String, value: Any?) = append(key).append('=').append(value ?: "").append('\n')
        put("filterColor", colorId)
        put("filterColorStrength", colorStrength)
        put("filterGrad", gradId)
        put("filterGradStrength", gradStrength)
        put("filterGradPosition", gradPosition)
        put("filterGradSoftness", gradSoftness)
        put("filterGradTilt", gradTilt)
        put("filterEffect", effectId)
        put("filterEffectStrength", effectStrength)
    }

    companion object {
        fun decode(text: String?): LensFilters = fromMap(
            text.orEmpty().lineSequence().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }.toMap(),
        )

        fun fromMap(map: Map<String, String>): LensFilters {
            fun id(key: String, slot: FilterSlot) = map[key]?.takeIf { FilterCatalog.find(it)?.slot == slot }
            fun f(key: String, default: Float) = map[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: default
            val grad = id("filterGrad", FilterSlot.GRAD)
            return LensFilters(
                colorId = id("filterColor", FilterSlot.COLOUR),
                colorStrength = f("filterColorStrength", 1f),
                gradId = grad,
                gradStrength = f("filterGradStrength", 1f),
                gradPosition = f("filterGradPosition", 0f),
                gradSoftness = f("filterGradSoftness", FilterCatalog.find(grad)?.edge ?: 0.3f),
                gradTilt = f("filterGradTilt", 0f),
                effectId = id("filterEffect", FilterSlot.EFFECT),
                effectStrength = f("filterEffectStrength", 1f),
            )
        }
    }
}
