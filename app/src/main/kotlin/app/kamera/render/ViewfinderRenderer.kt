package app.kamera.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import app.kamera.film.Affine
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmStock
import app.kamera.film.FilmUniforms
import app.kamera.film.FrameGeometry
import app.kamera.film.Framing
import app.kamera.film.Recipe
import app.kamera.gl.EglCore
import app.kamera.gl.FilmEngine
import app.kamera.gl.GlTextures
import app.kamera.gl.GlowBuffers
import app.kamera.gl.OutputTarget
import app.kamera.gl.RenderTarget
import app.kamera.gl.SourceFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/**
 * Owns the live picture: camera frames arrive on a [SurfaceTexture], pass through the film engine,
 * and are drawn to the screen and, while recording, to the video encoder from the same texture.
 */
class ViewfinderRenderer(private val context: Context, private val callbacks: Callbacks) {
    interface Callbacks {
        fun onTextureReady(texture: SurfaceTexture, maxTextureSize: Int)
        fun onHistogram(bins: FloatArray)
    }

    private val thread = HandlerThread("Viewfinder").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var egl: EglCore? = null
    private var engine: FilmEngine? = null
    private var idleSurface: EGLSurface? = null
    private var windowSurface: EGLSurface? = null
    private var windowWidth = 0
    private var windowHeight = 0

    private var oesTexture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val transform = FloatArray(16)

    private var bufferWidth = 1440
    private var bufferHeight = 1080
    private var sensorDegrees = 90
    private var displayTurns = 0
    private var realtimeClock = false

    private var stock: FilmStock = FilmCatalog.default
    private var recipe = Recipe(stock.id)
    private var frameAspect = 1.5f
    private var zebra = false
    private var peaking = false
    private var histogramEnabled = false

    private val viewGlow = GlowBuffers()
    private val recordGlow = GlowBuffers()
    private var histogramTarget: RenderTarget? = null
    private val histogramPixels = ByteBuffer.allocateDirect(HIST_W * HIST_H * 4).order(ByteOrder.nativeOrder())
    private var frameCount = 0L
    private var liveFrames = 0L

    private var recorder: VideoRecorder? = null
    private var encoderSurface: EGLSurface? = null
    private var recordTurns = 0

    private var grainX = 0f
    private var grainY = 0f
    private var grainTimeMs = 0L
    private var grainState = 0x2F6E2B1

    private val bakeQueue = LutBakeQueue { cube ->
        handler.post {
            engine?.uploadLut(cube)
            drawFrame(newFrame = false)
        }
    }

    fun start() {
        handler.post {
            val core = EglCore()
            egl = core
            val idle = core.createPbuffer()
            idleSurface = idle
            core.makeCurrent(idle)
            val film = FilmEngine(context.assets)
            engine = film
            oesTexture = GlTextures.createExternal()
            val texture = SurfaceTexture(oesTexture)
            texture.setOnFrameAvailableListener({ drawFrame(newFrame = true) }, handler)
            surfaceTexture = texture
            bakeQueue.request(stock, recipe.grade)
            val maxSize = film.maxTextureSize
            mainHandler.post { callbacks.onTextureReady(texture, maxSize) }
        }
    }

    fun attachWindow(surface: Surface, width: Int, height: Int) {
        handler.post {
            val core = egl ?: return@post
            detachWindowInternal()
            windowSurface = core.createWindowSurface(surface)
            windowWidth = width
            windowHeight = height
            drawFrame(newFrame = false)
        }
    }

    fun resizeWindow(width: Int, height: Int) {
        handler.post {
            windowWidth = width
            windowHeight = height
            drawFrame(newFrame = false)
        }
    }

    /** Must complete before the Surface is destroyed, so the caller blocks on it. */
    fun detachWindowBlocking() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            detachWindowInternal()
            done.countDown()
        }
        done.await(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun detachWindowInternal() {
        val core = egl ?: return
        windowSurface?.let {
            idleSurface?.let(core::makeCurrent)
            core.releaseSurface(it)
        }
        windowSurface = null
    }

    fun setCameraGeometry(width: Int, height: Int, sensor: Int, displayRotationDegrees: Int, timestampRealtime: Boolean) {
        handler.post {
            bufferWidth = width
            bufferHeight = height
            sensorDegrees = sensor
            displayTurns = displayRotationDegrees / 90
            realtimeClock = timestampRealtime
        }
    }

    fun setLook(newStock: FilmStock, newRecipe: Recipe) {
        handler.post {
            val colorChanged = newStock.id != stock.id || newRecipe.grade != recipe.grade
            stock = newStock
            recipe = newRecipe
            if (colorChanged) bakeQueue.request(newStock, newRecipe.grade) else drawFrame(newFrame = false)
        }
    }

    fun setFrameAspect(longEdgeAspect: Float) {
        handler.post {
            frameAspect = longEdgeAspect
            drawFrame(newFrame = false)
        }
    }

    fun setAssists(showZebra: Boolean, showPeaking: Boolean, histogram: Boolean) {
        handler.post {
            zebra = showZebra
            peaking = showPeaking
            histogramEnabled = histogram
        }
    }

    fun startRecording(videoRecorder: VideoRecorder, deviceDegrees: Int) {
        handler.post {
            val core = egl ?: return@post
            encoderSurface = core.createWindowSurface(videoRecorder.inputSurface)
            recordTurns = deviceDegrees / 90
            recorder = videoRecorder
        }
    }

    fun stopRecording(onStopped: (VideoRecorder.Result?) -> Unit) {
        handler.post {
            val core = egl
            val active = recorder
            recorder = null
            encoderSurface?.let { surface ->
                idleSurface?.let { core?.makeCurrent(it) }
                core?.releaseSurface(surface)
            }
            encoderSurface = null
            if (active == null) mainHandler.post { onStopped(null) } else active.stop { result -> mainHandler.post { onStopped(result) } }
        }
    }

    private fun naturalAspect(): Float =
        if (sensorDegrees % 180 != 0) bufferHeight.toFloat() / bufferWidth else bufferWidth.toFloat() / bufferHeight

    private fun drawFrame(newFrame: Boolean) {
        val texture = surfaceTexture ?: return
        val core = egl ?: return
        val film = engine ?: return
        val active = windowSurface ?: idleSurface ?: return
        try {
            core.makeCurrent(active)
            if (newFrame) texture.updateTexImage()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not update the camera frame", e)
            return
        }
        if (!film.isReady) return
        texture.getTransformMatrix(transform)
        val textureMap = Affine.fromMat4(transform)
        advanceGrain()
        if (newFrame && liveFrames++ % 240L == 0L) {
            Log.i(TAG, "Live frame $liveFrames ${bufferWidth}x$bufferHeight map=$textureMap window=${windowWidth}x$windowHeight err=${GLES30.glGetError()}")
        }

        recorder?.let { active2 -> if (newFrame) drawToEncoder(core, film, textureMap, active2, texture.timestamp) }

        val window = windowSurface ?: return
        core.makeCurrent(window)
        film.clear(0, windowWidth, windowHeight)
        if (windowWidth <= 0 || windowHeight <= 0) return

        val portrait = windowHeight >= windowWidth
        val aspect = if (portrait) 1f / frameAspect else frameAspect
        val (w, h) = FrameGeometry.fit(windowWidth, windowHeight, aspect)
        val matrix = textureMap * FrameGeometry.frameToSource(naturalAspect(), aspect, Framing(quarterTurns = displayTurns))
        val source = SourceFrame(oesTexture, true, bufferWidth, bufferHeight, matrix)
        val uniforms = FilmUniforms.compute(stock, recipe, w, h)
        film.updateGlow(viewGlow, source, uniforms, aspect)
        film.drawFilm(
            source,
            viewGlow,
            uniforms,
            OutputTarget(0, (windowWidth - w) / 2, (windowHeight - h) / 2, w, h),
            grainX,
            grainY,
            viewfinder = true,
            zebra = zebra,
            peaking = peaking,
        )
        if (histogramEnabled && newFrame && frameCount++ % HIST_INTERVAL == 0L) readHistogram(film, source, uniforms)
        core.swapBuffers(window)
    }

    private fun drawToEncoder(core: EglCore, film: FilmEngine, textureMap: Affine, active: VideoRecorder, timestampNs: Long) {
        val surface = encoderSurface ?: return
        val width = active.width
        val height = active.height
        val aspect = width.toFloat() / height
        val matrix = textureMap * FrameGeometry.frameToSource(
            naturalAspect(),
            aspect,
            Framing(quarterTurns = displayTurns + recordTurns),
        )
        core.makeCurrent(surface)
        val source = SourceFrame(oesTexture, true, bufferWidth, bufferHeight, matrix)
        val uniforms = FilmUniforms.compute(stock, recipe, width, height)
        film.updateGlow(recordGlow, source, uniforms, aspect)
        film.drawFilm(source, recordGlow, uniforms, OutputTarget(0, 0, 0, width, height), grainX, grainY)
        core.setPresentationTime(surface, toMonotonic(timestampNs))
        core.swapBuffers(surface)
    }

    /** Puts a sensor timestamp on `System.nanoTime()`'s clock so it lines up with the audio. */
    private fun toMonotonic(timestampNs: Long): Long {
        val now = System.nanoTime()
        val mapped = if (realtimeClock) timestampNs - (SystemClock.elapsedRealtimeNanos() - now) else timestampNs
        return if (abs(mapped - now) > MAX_CLOCK_SKEW_NS) now else mapped
    }

    /** Film grain is re-rolled at 24 fps, whatever rate the display runs at. */
    private fun advanceGrain() {
        val now = SystemClock.uptimeMillis()
        if (now - grainTimeMs < GRAIN_INTERVAL_MS) return
        grainTimeMs = now
        grainState = grainState * 1103515245 + 12345
        grainX = ((grainState ushr 8) and 0xFFF) / 4096f
        grainState = grainState * 1103515245 + 12345
        grainY = ((grainState ushr 8) and 0xFFF) / 4096f
    }

    private fun readHistogram(film: FilmEngine, source: SourceFrame, uniforms: FilmUniforms) {
        val target = histogramTarget ?: RenderTarget(HIST_W, HIST_H).also { histogramTarget = it }
        film.drawFilm(source, viewGlow, uniforms, OutputTarget(target.framebuffer, 0, 0, HIST_W, HIST_H))
        histogramPixels.position(0)
        GLES30.glReadPixels(0, 0, HIST_W, HIST_H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, histogramPixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        if (frameCount % 240L == 1L) {
            val r = histogramPixels.get(HIST_W * HIST_H * 2).toInt() and 0xFF
            Log.i(TAG, "Developed centre value $r glError=${GLES30.glGetError()}")
        }
        val bins = FloatArray(HIST_BINS)
        for (i in 0 until HIST_W * HIST_H) {
            val r = histogramPixels.get(i * 4).toInt() and 0xFF
            val g = histogramPixels.get(i * 4 + 1).toInt() and 0xFF
            val b = histogramPixels.get(i * 4 + 2).toInt() and 0xFF
            val luma = (54 * r + 183 * g + 19 * b) shr 8
            bins[luma * HIST_BINS / 256] += 1f
        }
        val peak = max(bins.max(), 1f)
        for (i in bins.indices) bins[i] /= peak
        mainHandler.post { callbacks.onHistogram(bins) }
    }

    fun release() {
        bakeQueue.shutdown()
        handler.post {
            val core = egl
            if (core != null) {
                idleSurface?.let(core::makeCurrent)
                recorder = null
                detachWindowInternal()
                viewGlow.release()
                recordGlow.release()
                histogramTarget?.release()
                surfaceTexture?.setOnFrameAvailableListener(null)
                surfaceTexture?.release()
                GlTextures.delete(oesTexture)
                engine?.release()
                idleSurface?.let(core::releaseSurface)
                core.release()
            }
            egl = null
            engine = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "KameraViewfinder"
        const val HIST_W = 96
        const val HIST_H = 64
        const val HIST_BINS = 64
        const val HIST_INTERVAL = 6L
        const val GRAIN_INTERVAL_MS = 41L
        const val MAX_CLOCK_SKEW_NS = 5_000_000_000L
    }
}
