package app.kamera.film

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GrainAndUniformsTest {
    @Test
    fun tileIsDeterministicAndNormalised() {
        val a = GrainTile.generate()
        assertArrayEquals(a, GrainTile.generate())
        for (channel in 0 until 4) {
            var sum = 0.0
            var squares = 0.0
            val count = GrainTile.SIZE * GrainTile.SIZE
            for (i in 0 until count) {
                val n = ((a[i * 4 + channel].toInt() and 0xFF) / 255.0 - 0.5) * 2.0 * GrainTile.RANGE
                sum += n
                squares += n * n
            }
            assertEquals(0.0, sum / count, 0.02)
            assertEquals(1.0, sqrt(squares / count), 0.03)
        }
    }

    @Test
    fun tileChannelsAreIndependent() {
        val a = GrainTile.generate()
        val count = GrainTile.SIZE * GrainTile.SIZE
        var cross = 0.0
        for (i in 0 until count) {
            val r = ((a[i * 4].toInt() and 0xFF) / 255.0 - 0.5) * 6.0
            val g = ((a[i * 4 + 1].toInt() and 0xFF) / 255.0 - 0.5) * 6.0
            cross += r * g
        }
        assertTrue(abs(cross / count) < 0.03)
    }

    @Test
    fun grainKeepsItsPhysicalSizeAtEveryResolution() {
        val stock = FilmCatalog.find("aura400")
        val recipe = Recipe(stock.id)
        val viewfinder = FilmUniforms.compute(stock, recipe, 1080, 1620)
        val photo = FilmUniforms.compute(stock, recipe, 4000, 6000)
        assertEquals(viewfinder.grainUvScaleX, photo.grainUvScaleX, 1e-3f)
        assertEquals(viewfinder.grainUvScaleY, photo.grainUvScaleY, 1e-3f)
        assertEquals(viewfinder.nearSigmaTexels, photo.nearSigmaTexels, 0f)
        assertEquals(viewfinder.farSigmaTexels, photo.farSigmaTexels, 0f)
        assertTrue("the coarser pixel grid averages more grain", viewfinder.grainLod > photo.grainLod)
    }

    @Test
    fun orientationDoesNotChangeGrainSize() {
        val stock = FilmCatalog.find("silver400")
        val recipe = Recipe(stock.id)
        val portrait = FilmUniforms.compute(stock, recipe, 2000, 3000)
        val landscape = FilmUniforms.compute(stock, recipe, 3000, 2000)
        assertEquals(portrait.grainUvScaleX, landscape.grainUvScaleY, 1e-3f)
        assertEquals(portrait.grainLod, landscape.grainLod, 0f)
    }

    @Test
    fun kelvinGainsWarmThePictureAboveTheBaseTemperature() {
        val warm = FilmMath.kelvinGains(8000f)
        val cool = FilmMath.kelvinGains(3200f)
        assertTrue(warm[0] > warm[2])
        assertTrue(cool[2] > cool[0])
        val neutral = FilmMath.kelvinGains(5500f)
        assertEquals(1f, neutral[0], 1e-3f)
        assertEquals(1f, neutral[2], 1e-3f)
    }
}
