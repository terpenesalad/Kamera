package app.kamera.film

import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Fixed resolutions of the glow buffers. They never depend on the output resolution. */
object GlowChain {
    /** Short edge of the buffer that carries halation and local-contrast data. */
    const val NEAR_SHORT_EDGE = 270

    /** Short edge of the buffer that carries wide bloom. */
    const val FAR_SHORT_EDGE = 90

    /** Largest blur sigma, in buffer texels, the kernel in `glow_blur.frag` can represent. */
    const val MAX_SIGMA_TEXELS = 5f
}

/**
 * Per-frame shader parameters derived from a stock, a recipe and an output resolution.
 *
 * All spatial quantities are expressed relative to the frame's short edge. That is what makes a
 * 1080p viewfinder and a 12 MP photograph statistically identical once viewed at the same size.
 */
class FilmUniforms(
    val inputGain: FloatArray,
    val grainAmount: Float,
    val grainChroma: Float,
    val grainShadow: Float,
    val grainUvScaleX: Float,
    val grainUvScaleY: Float,
    val grainLod: Float,
    val halationAmount: Float,
    val halationTint: FloatArray,
    val bloomAmount: Float,
    val nearSigmaTexels: Float,
    val farSigmaTexels: Float,
    val vignette: Float,
    val sharpen: Float,
    val clarity: Float,
) {
    companion object {
        /** Physical size of the frame's short edge, in micrometres (a 35 mm still frame). */
        private const val FRAME_SHORT_EDGE_UM = 24000f

        private const val GRAIN_LOD_COMPENSATION = 0.115f

        fun compute(
            stock: FilmStock,
            recipe: Recipe,
            outputWidth: Int,
            outputHeight: Int,
        ): FilmUniforms {
            val fx = recipe.effects
            val shortEdge = min(outputWidth, outputHeight).coerceAtLeast(1).toFloat()

            val exposureGain = 2f.pow(fx.exposure.coerceIn(-5f, 5f))
            val balance = whiteBalance(fx.temperature, fx.tint)

            val push = recipe.grade.push.coerceIn(-1f, 2f)
            val pushGrain = 1f + 0.38f * max(push, 0f) + 0.12f * min(push, 0f)
            val sizeUm = stock.grain.sizeUm * fx.grainSize.coerceIn(0.5f, 2.5f)

            // One tile texel equals one grain correlation length on the film plane.
            val tilesAcrossShortEdge = FRAME_SHORT_EDGE_UM / (sizeUm * GrainTile.SIZE)
            val texelsPerPixel = tilesAcrossShortEdge * GrainTile.SIZE / shortEdge
            val lod = log2(max(texelsPerPixel, 1f))

            // Trilinear mip sampling averages slightly more than a true box downscale of the final
            // image does. Measured on the GL bench; this keeps viewfinder grain equal to the photo's.
            val footprintGain = 1f + GRAIN_LOD_COMPENSATION * lod

            return FilmUniforms(
                inputGain = floatArrayOf(
                    exposureGain * balance[0],
                    exposureGain * balance[1],
                    exposureGain * balance[2],
                ),
                grainAmount = stock.grain.rms * fx.grainAmount.coerceIn(0f, 3f) * pushGrain * footprintGain,
                grainChroma = if (stock.isMonochrome) 0f else stock.grain.chroma,
                grainShadow = stock.grain.shadow,
                grainUvScaleX = tilesAcrossShortEdge * outputWidth / shortEdge,
                grainUvScaleY = tilesAcrossShortEdge * outputHeight / shortEdge,
                grainLod = lod,
                halationAmount = stock.halation.strength * fx.halation.coerceIn(0f, 3f),
                halationTint = stock.halation.tint.toArray(),
                bloomAmount = stock.bloom.strength * fx.bloom.coerceIn(0f, 3f),
                nearSigmaTexels = min(
                    stock.halation.radius * GlowChain.NEAR_SHORT_EDGE,
                    GlowChain.MAX_SIGMA_TEXELS,
                ),
                farSigmaTexels = min(
                    stock.bloom.radius * GlowChain.FAR_SHORT_EDGE,
                    GlowChain.MAX_SIGMA_TEXELS,
                ),
                vignette = stock.vignette * fx.vignette.coerceIn(0f, 4f),
                sharpen = fx.sharpen.coerceIn(0f, 1f),
                clarity = fx.clarity.coerceIn(-1f, 1f),
            )
        }

        /** Temperature and tint as luminance-preserving RGB gains. */
        fun whiteBalance(temperature: Float, tint: Float): FloatArray {
            val t = temperature.coerceIn(-1f, 1f) * 0.42f
            val m = tint.coerceIn(-1f, 1f) * 0.22f
            val r = 2f.pow(t + m * 0.5f)
            val g = 2f.pow(-m)
            val b = 2f.pow(-t + m * 0.5f)
            val norm = FilmMath.luma(r, g, b)
            return floatArrayOf(r / norm, g / norm, b / norm)
        }
    }
}
