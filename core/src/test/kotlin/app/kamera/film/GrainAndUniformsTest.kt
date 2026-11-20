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
    fun mipTableMatchesTheTile() {
        // FilmUniforms calibrates grain from this table, so it must describe the tile the GPU gets.
        val tile = GrainTile.generate()
        var n = GrainTile.SIZE
        var level = DoubleArray(n * n * 4) { (tile[it].toInt() and 255).toDouble() }
        for (lod in 0 until 6) {
            var std = 0.0
            for (c in 0 until 3) {
                var mean = 0.0
                var square = 0.0
                for (i in 0 until n * n) {
                    val v = (level[i * 4 + c] / 255.0 - 0.5) * 2.0 * GrainTile.RANGE
                    mean += v
                    square += v * v
                }
                mean /= n * n
                std += kotlin.math.sqrt(square / (n * n) - mean * mean) / 3.0
            }
            assertEquals("mip $lod", FilmUniforms.TILE_MIP_STD[lod].toDouble(), std, 0.002)
            val half = n / 2
            val next = DoubleArray(half * half * 4)
            for (y in 0 until half) for (x in 0 until half) for (c in 0 until 4) {
                val sum = level[((2 * y) * n + 2 * x) * 4 + c] + level[((2 * y) * n + 2 * x + 1) * 4 + c] +
                    level[((2 * y + 1) * n + 2 * x) * 4 + c] + level[((2 * y + 1) * n + 2 * x + 1) * 4 + c]
                next[(y * half + x) * 4 + c] = kotlin.math.floor(sum / 4 + 0.5)
            }
            level = next
            n = half
        }
    }

    @Test
    fun fullResolutionPhotosMinifyTheGrainTile() {
        // Magnifying a coarse tile turns grain into blocky pixels. Each texel is half a grain correlation
        // length, so up to about 1.7 pixels per texel still interpolates smoothly, even for 3200-speed film.
        for (stock in FilmCatalog.all.filter { it.grain.rmsGranularity > 0f }) {
            val u = FilmUniforms.compute(stock, Recipe(stock.id), 4000, 3000)
            val texelsPerPixel = u.grainUvScaleY * GrainTile.SIZE / 3000f
            assertTrue("${stock.id}: $texelsPerPixel texels per pixel", texelsPerPixel >= 0.6f)
        }
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
