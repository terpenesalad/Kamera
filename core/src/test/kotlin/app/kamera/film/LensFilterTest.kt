package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensFilterTest {
    private val colourStock = FilmCatalog.find("aura400")
    private val monoStock = FilmCatalog.find("silver400")

    private fun gains(stock: FilmStock, filters: LensFilters) =
        FilmUniforms.compute(stock, Recipe(stock.id, filters = filters), 3000, 2000).inputGain

    @Test
    fun catalogueIsWellFormed() {
        assertEquals(FilterCatalog.all.size, FilterCatalog.all.map { it.id }.toSet().size)
        for (slot in FilterSlot.entries) assertTrue(FilterCatalog.forSlot(slot).size >= 6)
    }

    @Test
    fun colourFiltersAreMeteredThrough() {
        for (filter in FilterCatalog.forSlot(FilterSlot.COLOUR).filter { !it.polariser }) {
            val g = gains(colourStock, LensFilters(colorId = filter.id))
            assertEquals(filter.id, 1f, FilmMath.luma(g[0], g[1], g[2]), 0.01f)
            val m = gains(monoStock, LensFilters(colorId = filter.id))
            val s = monoStock.sensitivity
            assertEquals(filter.id, 1f, s[0] * m[0] + s[1] * m[1] + s[2] * m[2], 0.01f)
        }
    }

    @Test
    fun warmingFiltersWarmAndCoolingFiltersCool() {
        val warm = gains(colourStock, LensFilters(colorId = "w81c"))
        val cool = gains(colourStock, LensFilters(colorId = "c82b"))
        assertTrue(warm[0] > warm[2])
        assertTrue(cool[2] > cool[0])
    }

    @Test
    fun aRedFilterDarkensBlueOnBlackAndWhiteFilm() {
        val g = gains(monoStock, LensFilters(colorId = "red"))
        assertTrue(g[2] < 0.2f && g[0] > 1.5f)
    }

    @Test
    fun emptyHolderChangesNothing() {
        val u = FilmUniforms.compute(colourStock, Recipe(colourStock.id), 3000, 2000)
        assertEquals(0f, u.gradStrength, 0f)
        assertEquals(0f, u.diffusion, 0f)
        assertEquals(0f, u.polariser, 0f)
        assertEquals(0, u.starPoints)
    }

    @Test
    fun effectsStayInsideTheBlurKernel() {
        for (filter in FilterCatalog.forSlot(FilterSlot.EFFECT)) {
            val u = FilmUniforms.compute(colourStock, Recipe(colourStock.id, filters = LensFilters(effectId = filter.id)), 4000, 3000)
            assertTrue(u.diffusionSigmaTexels <= GlowChain.MAX_SIGMA_TEXELS)
            assertTrue(u.diffusion < 1f)
        }
    }

    @Test
    fun holderSurvivesARoundTripAndRejectsWrongSlots() {
        val loaded = LensFilters("w81b", 0.7f, "tobacco2", 1.2f, 0.3f, 0.5f, -4f, "star8", 0.8f)
        assertEquals(loaded, LensFilters.decode(loaded.encode()))
        assertEquals(LensFilters(), LensFilters.decode("filterColor=star8\nfilterGrad=nonsense"))
    }
}
