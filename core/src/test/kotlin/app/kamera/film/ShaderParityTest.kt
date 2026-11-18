package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Fails when the constants in `common.glsl` drift away from their twins in [FilmMath]. */
class ShaderParityTest {
    private val shaderDir = listOf("../app/src/main/assets/shaders", "app/src/main/assets/shaders")
        .map(::File)
        .first { it.isDirectory }
    private val common = File(shaderDir, "common.glsl").readText()

    private fun constant(name: String): Float =
        Regex("""const float $name = (-?[0-9.]+);""").find(common)?.groupValues?.get(1)?.toFloat()
            ?: error("$name missing from common.glsl")

    @Test
    fun encodingConstantsMatch() {
        assertEquals(FilmMath.MID_GREY, constant("MID_GREY"), 0f)
        assertEquals(FilmMath.LOG_MIN_STOPS, constant("LOG_MIN_STOPS"), 0f)
        assertEquals(FilmMath.LOG_RANGE_STOPS, constant("LOG_RANGE_STOPS"), 0f)
        assertEquals(FilmMath.HIGHLIGHT_EXPANSION, constant("HIGHLIGHT_EXPANSION"), 0f)
    }

    @Test
    fun grainEncodingMatches() {
        val film = File(shaderDir, "film.frag").readText()
        assertTrue(film.contains("const float GRAIN_RANGE = ${GrainTile.RANGE.toInt()}.0;"))
    }

    @Test
    fun blurKernelCoversTheLargestSigma() {
        val blur = File(shaderDir, "glow_blur.frag").readText()
        val radius = Regex("""const int RADIUS = (\d+);""").find(blur)!!.groupValues[1].toInt()
        assertTrue(radius >= GlowChain.MAX_SIGMA_TEXELS * 2.4f)
    }
}
