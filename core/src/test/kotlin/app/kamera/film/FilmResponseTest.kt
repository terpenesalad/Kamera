package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FilmResponseTest {
    private val out = FloatArray(3)
    private val scratch = FloatArray(HueBands.COUNT)

    private fun neutral(response: FilmResponse, stops: Float): FloatArray {
        val encoded = (stops - FilmMath.LOG_MIN_STOPS) / FilmMath.LOG_RANGE_STOPS
        response.evaluate(encoded, encoded, encoded, out, scratch)
        return out.copyOf()
    }

    @Test
    fun catalogueIsWellFormed() {
        assertEquals(16, FilmCatalog.all.size)
        assertEquals(FilmCatalog.all.size, FilmCatalog.all.map { it.id }.toSet().size)
        for (stock in FilmCatalog.all) {
            assertEquals(stock.id, 3, stock.hueShift.size - HueBands.COUNT + 3)
            for (row in 0 until 3) {
                val sum = stock.sensitivity[row * 3] + stock.sensitivity[row * 3 + 1] + stock.sensitivity[row * 3 + 2]
                assertEquals("${stock.id} sensitivity row $row keeps neutrals neutral", 1f, sum, 0.001f)
                val inter = stock.interlayer[row * 3] + stock.interlayer[row * 3 + 1] + stock.interlayer[row * 3 + 2]
                assertEquals("${stock.id} interlayer row $row keeps neutrals neutral", 1f, inter, 0.001f)
            }
        }
    }

    @Test
    fun middleGreyLandsOnEachStocksDesignedValue() {
        for (stock in FilmCatalog.all) {
            val response = FilmResponse(stock, ColorGrade())
            val grey = neutral(response, 0f)
            val luma = FilmMath.luma(
                FilmMath.srgbToLinear(grey[0]),
                FilmMath.srgbToLinear(grey[1]),
                FilmMath.srgbToLinear(grey[2]),
            )
            val designed = FilmMath.luma(stock.mid.r, stock.mid.g, stock.mid.b)
            assertEquals(stock.id, designed, luma, 0.012f)
        }
    }

    @Test
    fun tonalScaleIsMonotonicForEveryStockAndExtremeGrade() {
        val grades = listOf(
            ColorGrade(),
            ColorGrade(contrast = 1f, highlights = 1f, shadows = 1f, whites = 1f, blacks = 1f),
            ColorGrade(contrast = -1f, highlights = -1f, shadows = -1f, whites = -1f, blacks = -1f, fade = 1f),
            ColorGrade(push = 2f, contrast = 1f),
            ColorGrade(push = -1f, contrast = -1f),
        )
        for (stock in FilmCatalog.all) for (grade in grades) {
            val response = FilmResponse(stock, grade)
            var previous = -1f
            var stops = -8f
            while (stops <= 6f) {
                val c = neutral(response, stops)
                val y = FilmMath.luma(c[0], c[1], c[2])
                assertTrue("${stock.id} $grade inverted at $stops stops ($previous -> $y)", y >= previous - 0.002f)
                previous = y
                stops += 0.05f
            }
        }
    }

    @Test
    fun outputStaysInRangeAcrossTheWholeCube() {
        for (stock in FilmCatalog.all) {
            val response = FilmResponse(stock, ColorGrade(saturation = 1f, vibrance = 1f))
            for (r in 0..8) for (g in 0..8) for (b in 0..8) {
                response.evaluate(r / 8f, g / 8f, b / 8f, out, scratch)
                for (v in out) assertTrue("${stock.id} produced $v", v.isFinite() && v in 0f..1f)
            }
        }
    }

    @Test
    fun monochromeStocksContainNoColour() {
        for (stock in FilmCatalog.all.filter { it.isMonochrome }) {
            val response = FilmResponse(stock, ColorGrade())
            response.evaluate(0.8f, 0.3f, 0.5f, out, scratch)
            assertEquals(stock.id, out[0], out[1], 0.004f)
            assertEquals(stock.id, out[1], out[2], 0.004f)
        }
    }

    @Test
    fun pushingRaisesContrastAroundTheSameMidpoint() {
        val stock = FilmCatalog.find("silver400")
        val normal = FilmResponse(stock, ColorGrade())
        val pushed = FilmResponse(stock, ColorGrade(push = 2f))
        assertEquals(neutral(normal, 0f)[1], neutral(pushed, 0f)[1], 0.02f)
        assertTrue(neutral(pushed, -3f)[1] < neutral(normal, -3f)[1])
    }

    @Test
    fun reversalIsContrastierThanNegative() {
        fun span(id: String): Float {
            val r = FilmResponse(FilmCatalog.find(id), ColorGrade())
            return abs(neutral(r, 1.5f)[1] - neutral(r, -1.5f)[1])
        }
        assertTrue(span("chroma50") > span("aura160"))
    }

    @Test
    fun noFilmReturnsTheSourcePictureUnchanged() {
        val response = FilmResponse(FilmCatalog.neutral, ColorGrade())
        for (display in listOf(0.1f, 0.25f, 0.5f, 0.75f, 0.95f)) {
            val e = FilmMath.sceneToLog(FilmMath.displayToScene(display))
            response.evaluate(e, e, e, out, scratch)
            assertEquals(display, out[1], 0.012f)
        }
    }
}
