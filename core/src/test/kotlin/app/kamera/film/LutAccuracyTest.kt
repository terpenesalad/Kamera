package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** The GPU only ever sees the baked cube, so the cube must faithfully represent the model. */
class LutAccuracyTest {
    private fun channel(pixel: Int, c: Int): Float = ((pixel ushr (10 * c)) and 0x3FF) / 1023f

    private fun lookup(lut: IntArray, size: Int, r: Float, g: Float, b: Float, out: FloatArray) {
        val fr = r * (size - 1)
        val fg = g * (size - 1)
        val fb = b * (size - 1)
        val r0 = min(floor(fr).toInt(), size - 2)
        val g0 = min(floor(fg).toInt(), size - 2)
        val b0 = min(floor(fb).toInt(), size - 2)
        val tr = fr - r0
        val tg = fg - g0
        val tb = fb - b0
        for (c in 0 until 3) {
            fun v(x: Int, y: Int, z: Int) = channel(lut[(b0 + z) * size * size + (g0 + y) * size + r0 + x], c)
            val c00 = FilmMath.lerp(v(0, 0, 0), v(1, 0, 0), tr)
            val c10 = FilmMath.lerp(v(0, 1, 0), v(1, 1, 0), tr)
            val c01 = FilmMath.lerp(v(0, 0, 1), v(1, 0, 1), tr)
            val c11 = FilmMath.lerp(v(0, 1, 1), v(1, 1, 1), tr)
            out[c] = FilmMath.lerp(FilmMath.lerp(c00, c10, tg), FilmMath.lerp(c01, c11, tg), tb)
        }
    }

    /** Largest channel error at each sampled colour, sorted ascending. */
    private fun errors(stock: FilmStock, sample: (Random) -> FloatArray): FloatArray {
        val random = Random(7)
        val scratch = FloatArray(HueBands.COUNT)
        val exact = FloatArray(3)
        val baked = FloatArray(3)
        val response = FilmResponse(stock, ColorGrade())
        val lut = LutBaker.bake(stock, ColorGrade())
        return FloatArray(4000) {
            val c = sample(random)
            val r = FilmMath.sceneToLog(FilmMath.displayToScene(c[0]))
            val g = FilmMath.sceneToLog(FilmMath.displayToScene(c[1]))
            val b = FilmMath.sceneToLog(FilmMath.displayToScene(c[2]))
            response.evaluate(r, g, b, exact, scratch)
            lookup(lut, LutBaker.SIZE, r, g, b, baked)
            max(max(abs(exact[0] - baked[0]), abs(exact[1] - baked[1])), abs(exact[2] - baked[2]))
        }.also { it.sort() }
    }

    @Test
    fun cubeIsAccurateForPhotographicColours() {
        // Real pictures cluster around neutral: a brightness plus a moderate colour offset.
        val photographic = { random: Random ->
            val base = 0.1f + 0.8f * random.nextFloat()
            FloatArray(3) { FilmMath.clamp01(base + (random.nextFloat() - 0.5f) * 0.3f) }
        }
        for (stock in FilmCatalog.all) {
            val e = errors(stock, photographic)
            assertTrue("${stock.id} mean ${e.average()}", e.average() < 0.006)
            assertTrue("${stock.id} p99 ${e[(e.size * 0.99f).toInt()]}", e[(e.size * 0.99f).toInt()] < 0.04f)
        }
    }

    @Test
    fun cubeStaysCloseEvenForExtremeColours() {
        for (stock in FilmCatalog.all) {
            val e = errors(stock) { random -> FloatArray(3) { random.nextFloat() } }
            assertTrue("${stock.id} mean ${e.average()}", e.average() < 0.008)
            assertTrue("${stock.id} p99 ${e[(e.size * 0.99f).toInt()]}", e[(e.size * 0.99f).toInt()] < 0.07f)
        }
    }

    @Test
    fun packingRoundTrips() {
        val p = LutBaker.pack(1f, 0.5f, 0f)
        assertEquals(1f, channel(p, 0), 0.001f)
        assertEquals(0.5f, channel(p, 1), 0.001f)
        assertEquals(0f, channel(p, 2), 0.001f)
    }
}
