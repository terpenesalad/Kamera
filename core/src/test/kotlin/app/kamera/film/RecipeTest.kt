package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeTest {
    @Test
    fun recipeSurvivesARoundTrip() {
        val recipe = Recipe(
            stockId = "nocturne800t",
            grade = ColorGrade(
                push = 1f,
                contrast = 0.25f,
                highlights = -0.4f,
                shadowToneAmount = 0.3f,
                mixerHue = listOf(0.1f, 0f, 0f, -0.2f, 0f, 0.5f, 0f),
            ),
            effects = Effects(exposure = 0.33f, grainAmount = 1.5f, clarity = -0.2f),
            framing = Framing(aspect = 1.5f, quarterTurns = 3, straighten = -2.5f, zoom = 1.4f, flipHorizontal = true),
            filters = LensFilters(colorId = "polariser", gradId = "g2s", gradPosition = 0.2f, effectId = "mist"),
        )
        assertEquals(recipe, Recipe.decode(recipe.encode()))
    }

    @Test
    fun missingAndCorruptValuesFallBackToDefaults() {
        val decoded = Recipe.decode("stock=aura400\ncontrast=oops\nmixerHue=1,2\nfuture=value")
        assertNotNull(decoded)
        assertEquals(Recipe("aura400"), decoded)
        assertNull(Recipe.decode("contrast=1"))
    }
}
