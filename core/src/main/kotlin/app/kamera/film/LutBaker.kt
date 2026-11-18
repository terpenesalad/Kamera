package app.kamera.film

import java.util.stream.IntStream

/** Bakes a [FilmResponse] into a 3D lookup table for the GPU. */
object LutBaker {
    /** Nodes per axis. Accuracy against the analytic model is enforced by `LutAccuracyTest`. */
    const val SIZE = 41

    /**
     * Returns `SIZE³` pixels packed as `GL_RGB10_A2` / `GL_UNSIGNED_INT_2_10_10_10_REV`,
     * ordered with R varying fastest, as `glTexImage3D` expects.
     */
    fun bake(stock: FilmStock, grade: ColorGrade, size: Int = SIZE): IntArray {
        val response = FilmResponse(stock, grade)
        val data = IntArray(size * size * size)
        val step = 1f / (size - 1)
        IntStream.range(0, size).parallel().forEach { b ->
            val out = FloatArray(3)
            val scratch = FloatArray(FilmResponse.SCRATCH_SIZE)
            var index = b * size * size
            for (g in 0 until size) {
                for (r in 0 until size) {
                    response.evaluate(r * step, g * step, b * step, out, scratch)
                    data[index++] = pack(out[0], out[1], out[2])
                }
            }
        }
        return data
    }

    fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (FilmMath.clamp01(r) * 1023f + 0.5f).toInt()
        val gi = (FilmMath.clamp01(g) * 1023f + 0.5f).toInt()
        val bi = (FilmMath.clamp01(b) * 1023f + 0.5f).toInt()
        return (3 shl 30) or (bi shl 20) or (gi shl 10) or ri
    }
}
