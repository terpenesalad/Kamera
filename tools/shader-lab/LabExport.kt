package app.kamera.lab

import app.kamera.film.ColorGrade
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmUniforms
import app.kamera.film.GrainTile
import app.kamera.film.LutBaker
import app.kamera.film.Recipe
import app.kamera.film.FilterCatalog
import app.kamera.film.FilterSlot
import app.kamera.film.LensFilters
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Exports LUTs, the grain tile and per-resolution uniforms for the headless GL test bench. */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    File(out, "grain.bin").writeBytes(GrainTile.generate())
    val sizes = listOf(750 to 500, 1080 to 720, 1620 to 1080, 3000 to 2000, 4500 to 3000)
    val json = StringBuilder("{")
    FilmCatalog.all.forEachIndexed { index, stock ->
        val lut = LutBaker.bake(stock, ColorGrade())
        val bytes = ByteBuffer.allocate(lut.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        lut.forEach(bytes::putInt)
        File(out, "${stock.id}.lut").writeBytes(bytes.array())
        // Local slope of the tone scale at middle grey, needed to read grain back as film density.
        val response = app.kamera.film.FilmResponse(stock, ColorGrade())
        fun outputStops(stops: Float): Float {
            val e = (stops + 8f) / 14f
            val c = FloatArray(3)
            response.evaluate(e, e, e, c, FloatArray(app.kamera.film.FilmResponse.SCRATCH_SIZE))
            return app.kamera.film.FilmMath.log2(app.kamera.film.FilmMath.srgbToLinear(c[1]))
        }
        val slope = (outputStops(0.25f) - outputStops(-0.25f)) / 0.5f
        if (index > 0) json.append(',')
        json.append("\"${stock.id}\":{\"name\":\"${stock.name}\",\"iso\":${stock.iso},\"rms\":${stock.grain.rmsGranularity},\"gamma\":${stock.chemistry.filmGamma},\"contrast\":$slope,\"sizes\":{")
        sizes.forEachIndexed { i, (w, h) ->
            if (i > 0) json.append(',')
            json.append("\"${w}x$h\":").append(uniformJson(FilmUniforms.compute(stock, Recipe(stock.id), w, h)))
        }
        json.append("}}")
    }
    File(out, "stocks.json").writeText(json.append('}').toString())

    // Every filter on a colour stock, and the black-and-white filters on a monochrome one.
    val filters = StringBuilder("{")
    var first = true
    fun addFilter(stockId: String, holder: LensFilters, key: String) {
        val stock = FilmCatalog.find(stockId)
        if (!first) filters.append(',')
        first = false
        filters.append("\"$key\":{\"lut\":\"$stockId\",\"name\":\"$key\",\"sizes\":{")
        sizes.forEachIndexed { i, (w, h) ->
            if (i > 0) filters.append(',')
            filters.append("\"${w}x$h\":").append(uniformJson(FilmUniforms.compute(stock, Recipe(stockId, filters = holder), w, h)))
        }
        filters.append("}}")
    }
    for (f in FilterCatalog.all) {
        val holder = when (f.slot) {
            FilterSlot.COLOUR -> LensFilters(colorId = f.id)
            FilterSlot.GRAD -> LensFilters(gradId = f.id, gradPosition = 0.15f, gradSoftness = f.edge)
            FilterSlot.EFFECT -> LensFilters(effectId = f.id)
        }
        addFilter("aura400", holder, "aura400|${f.id}")
        if (f.id in listOf("yellow", "orange", "red", "green", "polariser")) addFilter("silver400", holder, "silver400|${f.id}")
    }
    File(out, "filters.json").writeText(filters.append('}').toString())
}

private fun uniformJson(u: FilmUniforms): String = buildString {
    append("{\"gain\":[${u.inputGain.joinToString(",")}],")
    append("\"grainAmount\":${u.grainAmount},\"grainChroma\":${u.grainChroma},\"grainDensity\":[${u.grainDensityBase},${u.grainDensitySlope}],")
    append("\"grainUvScale\":[${u.grainUvScaleX},${u.grainUvScaleY}],\"grainLod\":${u.grainLod},")
    append("\"halation\":${u.halationAmount},\"halationTint\":[${u.halationTint.joinToString(",")}],")
    append("\"bloom\":${u.bloomAmount},\"nearSigma\":${u.nearSigmaTexels},\"farSigma\":${u.farSigmaTexels},")
    append("\"vignette\":${u.vignette},\"acutance\":${u.acutance},\"acutanceRadius\":${u.acutanceRadius},\"clarity\":${u.clarity},")
    append("\"gradTint\":[${u.gradTint.joinToString(",")}],\"gradStrength\":${u.gradStrength},\"gradPos\":${u.gradPosition},")
    append("\"gradSoft\":${u.gradSoftness},\"gradTilt\":${u.gradTilt},\"polariser\":${u.polariser},")
    append("\"diffusion\":${u.diffusion},\"diffusionSigma\":${u.diffusionSigmaTexels},\"diffusionSpot\":${u.diffusionSpot},\"pastel\":${u.pastel},")
    append("\"starPoints\":${u.starPoints},\"starDecay\":${u.starDecayTexels},\"starStrength\":${u.starStrength}}")
}
