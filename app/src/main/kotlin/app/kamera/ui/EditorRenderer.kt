package app.kamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import app.kamera.film.Affine
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmUniforms
import app.kamera.film.FrameGeometry
import app.kamera.film.Recipe
import app.kamera.gl.EglCore
import app.kamera.gl.FilmEngine
import app.kamera.gl.GlTextures
import app.kamera.gl.GlowBuffers
import app.kamera.gl.OutputTarget
import app.kamera.gl.SourceFrame
import app.kamera.render.LutBakeQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Live preview for the editor, driven by the same engine that exports the final file. */
class EditorRenderer(private val context: Context) {
    private val thread = HandlerThread("EditorPreview").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())

    private var egl: EglCore? = null
    private var engine: FilmEngine? = null
    private var idle: EGLSurface? = null
    private var window: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0
    private val glow = GlowBuffers()

    private var texture = 0
    private var external = false
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private val transform = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

    private var recipe = Recipe(FilmCatalog.default.id)
    private var showOriginal = false

    private val bakeQueue = LutBakeQueue { cube ->
        handler.post {
            engine?.uploadLut(cube)
            draw()
        }
    }

    fun start() {
        handler.post {
            val core = EglCore()
            egl = core
            val pbuffer = core.createPbuffer()
            idle = pbuffer
            core.makeCurrent(pbuffer)
            engine = FilmEngine(context.assets)
        }
    }

    fun attachWindow(surface: Surface, width: Int, height: Int) {
        handler.post {
            val core = egl ?: return@post
            releaseWindow()
            window = core.createWindowSurface(surface)
            windowWidth = width
            windowHeight = height
            draw()
        }
    }

    fun detachWindowBlocking() {
        val latch = CountDownLatch(1)
        handler.post {
            releaseWindow()
            latch.countDown()
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    private fun releaseWindow() {
        val core = egl ?: return
        window?.let {
            idle?.let(core::makeCurrent)
            core.releaseSurface(it)
        }
        window = null
    }

    fun setPicture(bitmap: Bitmap) {
        handler.post {
            idle?.let { egl?.makeCurrent(window ?: it) }
            if (texture != 0) GlTextures.delete(texture)
            texture = GlTextures.create2D(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            external = false
            sourceWidth = bitmap.width
            sourceHeight = bitmap.height
            draw()
        }
    }

    /** Prepares a video source and hands back the surface a player should render into. */
    fun prepareVideo(width: Int, height: Int, onSurface: (Surface) -> Unit) {
        handler.post {
            idle?.let { egl?.makeCurrent(window ?: it) }
            texture = GlTextures.createExternal()
            external = true
            sourceWidth = width
            sourceHeight = height
            val st = SurfaceTexture(texture).apply { setDefaultBufferSize(width, height) }
            st.setOnFrameAvailableListener({
                val current = window ?: idle
                if (current != null) {
                    egl?.makeCurrent(current)
                    st.updateTexImage()
                    st.getTransformMatrix(transform)
                    draw()
                }
            }, handler)
            surfaceTexture = st
            val surface = Surface(st)
            videoSurface = surface
            main.post { onSurface(surface) }
        }
    }

    fun setRecipe(next: Recipe) {
        handler.post {
            val colorChanged = next.stockId != recipe.stockId || next.grade != recipe.grade || engine?.isReady != true
            recipe = next
            if (colorChanged) bakeQueue.request(FilmCatalog.find(next.stockId), next.grade) else draw()
        }
    }

    fun setShowOriginal(show: Boolean) {
        handler.post {
            showOriginal = show
            draw()
        }
    }

    private fun draw() {
        val core = egl ?: return
        val film = engine ?: return
        val target = window ?: return
        if (sourceWidth == 0 || !film.isReady) return
        core.makeCurrent(target)
        film.clear(0, windowWidth, windowHeight)
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val aspect = FrameGeometry.outputAspect(sourceAspect, recipe.framing)
        val (w, h) = FrameGeometry.fit(windowWidth, windowHeight, aspect)
        val map = FrameGeometry.frameToSource(sourceAspect, aspect, recipe.framing)
        val matrix = if (external) Affine.fromMat4(transform) * map else Affine.FLIP_V * map
        val source = SourceFrame(texture, external, sourceWidth, sourceHeight, matrix)
        val output = OutputTarget(0, (windowWidth - w) / 2, (windowHeight - h) / 2, w, h)
        if (showOriginal) {
            film.drawOriginal(source, output)
        } else {
            val stock = FilmCatalog.find(recipe.stockId)
            val uniforms = FilmUniforms.compute(stock, recipe, w, h)
            film.updateGlow(glow, source, uniforms, aspect)
            film.drawFilm(source, glow, uniforms, output)
        }
        core.swapBuffers(target)
    }

    fun release() {
        bakeQueue.shutdown()
        handler.post {
            val core = egl
            if (core != null) {
                idle?.let(core::makeCurrent)
                releaseWindow()
                glow.release()
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                videoSurface?.release()
                if (texture != 0) GlTextures.delete(texture)
                engine?.release()
                idle?.let(core::releaseSurface)
                core.release()
            }
            egl = null
            engine = null
            thread.quitSafely()
        }
    }
}
