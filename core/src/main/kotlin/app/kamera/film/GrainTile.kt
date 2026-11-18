package app.kamera.film

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Deterministic, seamlessly tiling Gaussian noise.
 *
 * The four channels are statistically independent: RGB drive the dye layers, alpha drives dither.
 * Each channel is low-pass filtered so one texel equals one grain correlation length.
 */
object GrainTile {
    const val SIZE = 512

    private const val SIGMA_TEXELS = 0.72f
    private const val KERNEL_RADIUS = 3

    /** Standard deviations stored in the 8-bit range: value = 0.5 + n / (2 * RANGE). */
    const val RANGE = 3f

    /** Returns `SIZE² × 4` bytes of RGBA8 data. */
    fun generate(seed: Long = 0x4B414D455241L): ByteArray {
        val output = ByteArray(SIZE * SIZE * 4)
        val kernel = gaussianKernel()
        var state = seed
        for (channel in 0 until 4) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val field = whiteNoise(state)
            val filtered = filterWrapped(field, kernel)
            normalise(filtered)
            for (i in filtered.indices) {
                val v = 0.5f + filtered[i] / (2f * RANGE)
                output[i * 4 + channel] = (min(max(v, 0f), 1f) * 255f + 0.5f).toInt().toByte()
            }
        }
        return output
    }

    private fun gaussianKernel(): FloatArray {
        val k = FloatArray(KERNEL_RADIUS * 2 + 1)
        var sum = 0f
        for (i in k.indices) {
            val d = (i - KERNEL_RADIUS).toFloat()
            k[i] = exp(-(d * d) / (2f * SIGMA_TEXELS * SIGMA_TEXELS))
            sum += k[i]
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    private fun whiteNoise(seed: Long): FloatArray {
        var x = seed or 1L
        fun next(): Double {
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            return ((x ushr 11).toDouble() + 1.0) / 9007199254740994.0
        }
        return FloatArray(SIZE * SIZE) {
            val u = next()
            val v = next()
            (sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)).toFloat()
        }
    }

    private fun filterWrapped(src: FloatArray, kernel: FloatArray): FloatArray {
        val mask = SIZE - 1
        val horizontal = FloatArray(src.size)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * src[y * SIZE + ((x + k - KERNEL_RADIUS) and mask)]
                horizontal[y * SIZE + x] = sum
            }
        }
        val out = FloatArray(src.size)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                var sum = 0f
                for (k in kernel.indices) sum += kernel[k] * horizontal[((y + k - KERNEL_RADIUS) and mask) * SIZE + x]
                out[y * SIZE + x] = sum
            }
        }
        return out
    }

    private fun normalise(field: FloatArray) {
        val mean = field.average().toFloat()
        var variance = 0f
        for (i in field.indices) {
            field[i] -= mean
            variance += field[i] * field[i]
        }
        val scale = 1f / sqrt(variance / field.size)
        for (i in field.indices) field[i] *= scale
    }
}
