package app.kamera.gl

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES30
import app.kamera.film.Affine
import app.kamera.film.FilmUniforms
import app.kamera.film.GlowChain
import app.kamera.film.GrainTile
import app.kamera.film.LutBaker
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A picture to develop: a texture plus the map from output-frame UV to texture UV. */
class SourceFrame(
    val texture: Int,
    val width: Int,
    val height: Int,
    val matrix: Affine,
)

/** Where a pass draws. `flipY` stores the image top-row-first for readback into a Bitmap. */
class OutputTarget(
    val framebuffer: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val flipY: Boolean = false,
)

/** Fixed-resolution buffers holding the scattered light of one output shape. */
class GlowBuffers {
    private var key = ""
    internal var levels: List<RenderTarget> = emptyList()
    internal lateinit var nearA: RenderTarget
    internal lateinit var nearB: RenderTarget
    internal lateinit var mid: RenderTarget
    internal lateinit var farSource: RenderTarget
    internal lateinit var farA: RenderTarget
    internal lateinit var farB: RenderTarget
    internal var firstLevelShortEdge = GlowChain.NEAR_SHORT_EDGE

    internal fun ensure(sourceShortEdge: Int, frameAspect: Float) {
        val newKey = "$sourceShortEdge@${(frameAspect * 1000).roundToInt()}"
        if (newKey == key) return
        release()
        key = newKey
        fun target(shortEdge: Int): RenderTarget =
            if (frameAspect >= 1f) {
                RenderTarget((shortEdge * frameAspect).roundToInt(), shortEdge)
            } else {
                RenderTarget(shortEdge, (shortEdge / frameAspect).roundToInt())
            }

        val edges = ArrayList<Int>()
        var edge = sourceShortEdge
        while (edge / 2 >= GlowChain.NEAR_SHORT_EDGE * 2) {
            edge /= 2
            edges.add(edge)
        }
        edges.add(GlowChain.NEAR_SHORT_EDGE)
        firstLevelShortEdge = edges.first()
        levels = edges.map(::target)
        nearA = target(GlowChain.NEAR_SHORT_EDGE)
        nearB = target(GlowChain.NEAR_SHORT_EDGE)
        mid = target(GlowChain.NEAR_SHORT_EDGE / 2)
        farSource = target(GlowChain.FAR_SHORT_EDGE)
        farA = target(GlowChain.FAR_SHORT_EDGE)
        farB = target(GlowChain.FAR_SHORT_EDGE)
    }

    fun release() {
        if (key.isEmpty()) return
        levels.forEach(RenderTarget::release)
        listOf(nearA, nearB, mid, farSource, farA, farB).forEach(RenderTarget::release)
        levels = emptyList()
        key = ""
    }
}

/**
 * The single film renderer. Every picture Kamera produces, on any surface, passes through
 * [updateGlow] and [drawFilm], which is what keeps the viewfinder and the final image identical.
 */
class FilmEngine(assets: AssetManager) {
    private val library = ShaderLibrary(assets)
    private val programs = HashMap<String, GlProgram>()
    private val vertexArray: Int
    private val vertexBuffer: Int
    private val grainTexture: Int
    private val lutTexture: Int
    private var lutLoaded = false
    private var resolveTarget: RenderTarget? = null
    private val resolveProgram: GlProgram by lazy { library.buildExternalResolve() }

    val maxTextureSize: Int

    init {
        val size = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, size, 0)
        maxTextureSize = size[0]

        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        vertexArray = ids[0]
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glGenBuffers(1, ids, 0)
        vertexBuffer = ids[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBuffer)
        val quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
        quad.position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 8 * 4, quad, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, 0)

        grainTexture = GlTextures.create2D(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_REPEAT)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, GrainTile.SIZE, GrainTile.SIZE, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(GrainData.tile.size).order(ByteOrder.nativeOrder()).put(GrainData.tile).apply { position(0) },
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        GLES30.glGenTextures(1, ids, 0)
        lutTexture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        for ((name, value) in listOf(
            GLES30.GL_TEXTURE_MIN_FILTER to GLES30.GL_LINEAR,
            GLES30.GL_TEXTURE_MAG_FILTER to GLES30.GL_LINEAR,
            GLES30.GL_TEXTURE_WRAP_S to GLES30.GL_CLAMP_TO_EDGE,
            GLES30.GL_TEXTURE_WRAP_T to GLES30.GL_CLAMP_TO_EDGE,
            GLES30.GL_TEXTURE_WRAP_R to GLES30.GL_CLAMP_TO_EDGE,
        )) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, name, value)
        }
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDisable(GLES30.GL_DITHER)
    }

    val isReady: Boolean get() = lutLoaded

    /** Installs a cube produced by [LutBaker]. */
    fun uploadLut(cube: IntArray) {
        val size = LutBaker.SIZE
        val buffer = ByteBuffer.allocateDirect(cube.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(cube)
        buffer.position(0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB10_A2, size, size, size, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_INT_2_10_10_10_REV, buffer,
        )
        lutLoaded = true
    }

    private fun program(fragment: String, viewfinder: Boolean = false): GlProgram =
        programs.getOrPut("$fragment/$viewfinder") { library.build(fragment, viewfinder) }

    /**
     * Copies an external camera or video frame into a regular texture, keeping its buffer layout,
     * so the rest of the pipeline treats every source the same way.
     */
    fun resolveExternal(externalTexture: Int, width: Int, height: Int): Int {
        val current = resolveTarget
        val target = if (current != null && current.width == width && current.height == height) {
            current
        } else {
            current?.release()
            RenderTarget(width, height).also { resolveTarget = it }
        }
        resolveProgram.use()
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, externalTexture)
        resolveProgram.set1i("uFrame", 0)
        drawQuad()
        return target.texture
    }

    private fun begin(program: GlProgram, target: RenderTarget, matrix: Affine = Affine.IDENTITY) {
        program.use()
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(0, 0, target.width, target.height)
        program.setMat4("uSourceMatrix", matrix.toMat4())
        program.set1f("uFlipY", 1f)
    }

    private fun bind2D(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    private fun drawQuad() = GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)

    /** Renders the scattered-light buffers for [source] as seen through the output frame. */
    fun updateGlow(glow: GlowBuffers, source: SourceFrame, u: FilmUniforms, frameAspect: Float) {
        val sourceShort = max(min(source.width, source.height), GlowChain.NEAR_SHORT_EDGE)
        glow.ensure(sourceShort, frameAspect)

        val pre = program("glow_prefilter.frag")
        begin(pre, glow.levels[0], source.matrix)
        bind2D(0, source.texture)
        pre.set1i("uSource", 0)
        pre.set3f("uInputGain", u.inputGain)
        val ratio = sourceShort.toFloat() / glow.firstLevelShortEdge
        pre.set2f("uSrcTexel", ratio * 0.5f / source.width, ratio * 0.5f / source.height)
        drawQuad()

        for (i in 1 until glow.levels.size) downsample(glow.levels[i - 1], glow.levels[i])
        val nearSource = glow.levels.last()
        blur(nearSource, glow.nearA, glow.nearB, u.nearSigmaTexels)
        downsample(nearSource, glow.mid)
        downsample(glow.mid, glow.farSource)
        blur(glow.farSource, glow.farA, glow.farB, u.farSigmaTexels)
    }

    private fun downsample(from: RenderTarget, to: RenderTarget) {
        val p = program("glow_downsample.frag")
        begin(p, to)
        bind2D(0, from.texture)
        p.set1i("uInput", 0)
        val ratio = from.height.toFloat() / to.height
        p.set2f("uTexel", ratio * 0.5f / from.width, ratio * 0.5f / from.height)
        drawQuad()
    }

    private fun blur(source: RenderTarget, a: RenderTarget, b: RenderTarget, sigma: Float) {
        val p = program("glow_blur.frag")
        begin(p, a)
        bind2D(0, source.texture)
        p.set1i("uInput", 0)
        p.set1f("uSigma", sigma)
        p.set2f("uDirection", 1f / source.width, 0f)
        drawQuad()
        begin(p, b)
        bind2D(0, a.texture)
        p.set1i("uInput", 0)
        p.set1f("uSigma", sigma)
        p.set2f("uDirection", 0f, 1f / source.height)
        drawQuad()
    }

    /** Runs the film pipeline into [target]. [updateGlow] must have run for this frame. */
    fun drawFilm(
        source: SourceFrame,
        glow: GlowBuffers,
        u: FilmUniforms,
        target: OutputTarget,
        grainOffsetX: Float = 0f,
        grainOffsetY: Float = 0f,
        viewfinder: Boolean = false,
        zebra: Boolean = false,
        peaking: Boolean = false,
    ) {
        val p = program("film.frag", viewfinder)
        p.use()
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(target.x, target.y, target.width, target.height)
        p.setMat4("uSourceMatrix", source.matrix.toMat4())
        p.set1f("uFlipY", if (target.flipY) -1f else 1f)

        bind2D(0, source.texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        bind2D(2, grainTexture)
        bind2D(3, glow.nearB.texture)
        bind2D(4, glow.farB.texture)
        p.set1i("uSource", 0)
        p.set1i("uLut", 1)
        p.set1i("uGrain", 2)
        p.set1i("uGlowNear", 3)
        p.set1i("uGlowFar", 4)

        p.set3f("uInputGain", u.inputGain)
        p.set1f("uLutSize", LutBaker.SIZE.toFloat())
        p.set1f("uGrainAmount", u.grainAmount)
        p.set1f("uGrainChroma", u.grainChroma)
        p.set2f("uGrainDensity", u.grainDensityBase, u.grainDensitySlope)
        p.set2f("uGrainUvScale", u.grainUvScaleX, u.grainUvScaleY)
        p.set2f("uGrainOffset", grainOffsetX, grainOffsetY)
        p.set1f("uGrainLod", u.grainLod)
        p.set1f("uHalation", u.halationAmount)
        p.set3f("uHalationTint", u.halationTint)
        p.set1f("uBloom", u.bloomAmount)
        p.set1f("uVignette", u.vignette)
        p.set1f("uClarity", u.clarity)
        p.set1f("uAcutance", u.acutance)
        val m = source.matrix
        val radius = u.acutanceRadius
        p.set2f("uSrcStepX", m.a * radius / target.width, m.d * radius / target.width)
        p.set2f("uSrcStepY", m.b * radius / target.height, m.e * radius / target.height)
        if (viewfinder) {
            p.set1f("uZebra", if (zebra) 1f else 0f)
            p.set1f("uPeaking", if (peaking) 1f else 0f)
        }
        drawQuad()
    }

    /** Shows the unprocessed source, used by the editor's before/after comparison. */
    fun drawOriginal(source: SourceFrame, target: OutputTarget) {
        val p = program("passthrough.frag")
        p.use()
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, target.framebuffer)
        GLES30.glViewport(target.x, target.y, target.width, target.height)
        p.setMat4("uSourceMatrix", source.matrix.toMat4())
        p.set1f("uFlipY", if (target.flipY) -1f else 1f)
        bind2D(0, source.texture)
        p.set1i("uSource", 0)
        drawQuad()
    }

    /** Converts tightly packed YUV planes into an sRGB [target], mapped through [matrix]. */
    fun drawYuv(lumaTexture: Int, chromaTexture: Int, matrix: Affine, target: RenderTarget) {
        val p = program("yuv_to_rgb.frag")
        begin(p, target, matrix)
        bind2D(0, lumaTexture)
        bind2D(1, chromaTexture)
        p.set1i("uLumaTex", 0)
        p.set1i("uChromaTex", 1)
        drawQuad()
    }

    fun clear(framebuffer: Int, width: Int, height: Int) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    }

    fun release() {
        programs.values.forEach(GlProgram::release)
        programs.clear()
        resolveTarget?.release()
        resolveTarget = null
        GlTextures.delete(grainTexture)
        GlTextures.delete(lutTexture)
        GLES30.glDeleteBuffers(1, intArrayOf(vertexBuffer), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
    }
}

/** The grain tile is expensive to synthesise, so one copy serves every GL context. */
private object GrainData {
    val tile: ByteArray by lazy { GrainTile.generate() }
}
