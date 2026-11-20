package app.kamera.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import app.kamera.camera.StillCapture
import app.kamera.codec.JpegEncoder
import app.kamera.data.Library
import app.kamera.data.LibraryItem
import app.kamera.data.MediaKind
import app.kamera.data.MediaStoreWriter
import app.kamera.film.Affine
import app.kamera.film.CaptureGeometry
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmUniforms
import app.kamera.film.FrameGeometry
import app.kamera.film.Recipe
import app.kamera.gl.EglCore
import app.kamera.gl.FilmEngine
import app.kamera.gl.GlTextures
import app.kamera.gl.GlowBuffers
import app.kamera.gl.OutputTarget
import app.kamera.gl.RenderTarget
import app.kamera.gl.SourceFrame
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The darkroom: turns captures into finished photographs on its own thread and GL context.
 * Each picture runs through the same [FilmEngine] calls the viewfinder uses.
 */
class PhotoDeveloper(
    private val context: Context,
    private val library: Library,
    private val writer: MediaStoreWriter,
) {
    private val thread = HandlerThread("Darkroom").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val queued = AtomicInteger(0)

    private var egl: EglCore? = null
    private var engine: FilmEngine? = null
    private val glow = GlowBuffers()

    /** Number of pictures waiting in the darkroom; the shutter pauses when this gets too high. */
    val backlog: Int get() = queued.get()

    private fun gl(): FilmEngine {
        engine?.let { return it }
        val core = EglCore()
        core.makeCurrent(core.createPbuffer())
        egl = core
        return FilmEngine(context.assets).also { engine = it }
    }

    private fun enqueue(work: () -> Unit) {
        queued.incrementAndGet()
        handler.post {
            try {
                work()
            } finally {
                queued.decrementAndGet()
            }
        }
    }

    /** Develops a fresh capture and files it in the library. */
    fun developCapture(still: StillCapture, recipe: Recipe, onDone: (LibraryItem?) -> Unit) {
        enqueue {
            val item = runCatching { developCaptureInternal(still, recipe) }
                .onFailure { Log.e(TAG, "Developing failed", it) }
                .getOrNull()
            main.post { onDone(item) }
        }
    }

    private fun developCaptureInternal(still: StillCapture, recipe: Recipe): LibraryItem {
        val startedMs = android.os.SystemClock.elapsedRealtime()
        val film = gl()
        val luma = GlTextures.create2D(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, still.width, still.height, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, still.luma,
        )
        val chroma = GlTextures.create2D(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, still.width / 2, still.height / 2, 0,
            GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, still.chroma,
        )
        val (negW, negH) = CaptureGeometry.negativeSize(still.width, still.height, still.sensorDegrees, still.deviceDegrees)
        val negative = RenderTarget(negW, negH)
        film.drawYuv(
            luma,
            chroma,
            CaptureGeometry.negativeToBuffer(still.width, still.height, still.sensorDegrees, still.frontFacing, still.deviceDegrees),
            negative,
        )
        GlTextures.delete(luma)
        GlTextures.delete(chroma)

        val placeholder = library.newItem(MediaKind.PHOTO, negW, negH, 0L, null, filmBaked = false)
        try {
            writeJpeg(readTexture(film, negative), placeholder.negative, NEGATIVE_QUALITY)
            placeholder.writeRecipe(recipe)
            val developed = developTexture(film, negative.texture, negW, negH, flippedV = false, recipe)
            val item = publish(placeholder, developed, existing = null)
            Log.i(TAG, "Developed ${item.width}x${item.height} on ${recipe.stockId} in ${android.os.SystemClock.elapsedRealtime() - startedMs} ms")
            return item
        } finally {
            negative.release()
        }
    }

    /** Develops an existing negative again with a new recipe. */
    fun redevelop(item: LibraryItem, recipe: Recipe, onDone: (LibraryItem?) -> Unit) {
        enqueue {
            val result = runCatching {
                val film = gl()
                val negative = requireNotNull(BitmapFactory.decodeFile(item.negative.path)) { "Negative is missing" }
                val developed = developBitmap(film, negative, recipe)
                negative.recycle()
                item.writeRecipe(recipe)
                publish(item, developed, existing = item.mediaUri)
            }.onFailure { Log.e(TAG, "Developing failed", it) }.getOrNull()
            main.post { onDone(result) }
        }
    }

    /** Brings an outside photo into the library as a new negative. */
    fun importPhoto(uri: Uri, stockId: String, onDone: (LibraryItem?) -> Unit) {
        enqueue {
            val result = runCatching {
                val film = gl()
                val limit = minOf(film.maxTextureSize, MAX_IMPORT_EDGE)
                val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val edge = max(info.size.width, info.size.height)
                    if (edge > limit) {
                        val scale = limit.toFloat() / edge
                        decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
                    }
                }
                val bitmap = decoded.copy(Bitmap.Config.ARGB_8888, false)
                decoded.recycle()
                val recipe = Recipe(stockId)
                val item = library.newItem(MediaKind.PHOTO, bitmap.width, bitmap.height, 0L, null, filmBaked = false)
                writeJpeg(Pixels.of(bitmap), item.negative, NEGATIVE_QUALITY)
                item.writeRecipe(recipe)
                val developed = developBitmap(film, bitmap, recipe)
                bitmap.recycle()
                publish(item, developed, existing = null)
            }.onFailure { Log.e(TAG, "Import failed", it) }.getOrNull()
            main.post { onDone(result) }
        }
    }

    private fun developBitmap(film: FilmEngine, negative: Bitmap, recipe: Recipe): Pixels {
        val texture = GlTextures.create2D(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, negative, 0)
        try {
            return developTexture(film, texture, negative.width, negative.height, flippedV = true, recipe)
        } finally {
            GlTextures.delete(texture)
        }
    }

    private fun developTexture(
        film: FilmEngine,
        texture: Int,
        width: Int,
        height: Int,
        flippedV: Boolean,
        recipe: Recipe,
    ): Pixels {
        val stock = FilmCatalog.find(recipe.stockId)
        film.uploadLut(LutStore.get(stock, recipe.grade))
        val (outW, outH) = FrameGeometry.outputSize(width, height, recipe.framing)
        val aspect = outW.toFloat() / outH
        val map = FrameGeometry.frameToSource(width.toFloat() / height, aspect, recipe.framing)
        val source = SourceFrame(texture, width, height, if (flippedV) Affine.FLIP_V * map else map)
        val uniforms = FilmUniforms.compute(stock, recipe, outW, outH)
        film.updateGlow(glow, source, uniforms, aspect)
        val target = RenderTarget(outW, outH)
        film.drawFilm(source, glow, uniforms, OutputTarget(target.framebuffer, 0, 0, outW, outH, flipY = true))
        val pixels = readPixels(target.framebuffer, outW, outH)
        target.release()
        glow.release()
        return pixels
    }

    /** Reads a bottom-up render target as normal top-down pixels. */
    private fun readTexture(film: FilmEngine, source: RenderTarget): Pixels {
        val flipped = RenderTarget(source.width, source.height)
        film.drawOriginal(
            SourceFrame(source.texture, source.width, source.height, Affine.IDENTITY),
            OutputTarget(flipped.framebuffer, 0, 0, source.width, source.height, flipY = true),
        )
        val pixels = readPixels(flipped.framebuffer, source.width, source.height)
        flipped.release()
        return pixels
    }

    private fun readPixels(framebuffer: Int, width: Int, height: Int): Pixels {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pixels.position(0)
        return Pixels(pixels, width, height)
    }

    /** Full-colour (4:4:4) JPEG straight from pixels: no EXIF, location, date or device data. */
    private fun writeJpeg(pixels: Pixels, file: File, quality: Int) {
        file.outputStream().buffered(1 shl 16).use { JpegEncoder.encode(pixels.buffer, pixels.width, pixels.height, quality, it) }
    }

    private fun publish(
        item: LibraryItem,
        developed: Pixels,
        existing: Uri?,
    ): LibraryItem {
        val thumb = thumbnailOf(developed)
        item.thumbnail.outputStream().buffered().use { thumb.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, it) }
        thumb.recycle()

        val temp = File.createTempFile("develop", ".jpg", context.cacheDir)
        try {
            val encodeStart = android.os.SystemClock.elapsedRealtime()
            writeJpeg(developed, temp, FINAL_QUALITY)
            Log.i(TAG, "Encoded ${developed.width}x${developed.height} at q$FINAL_QUALITY 4:4:4 in ${android.os.SystemClock.elapsedRealtime() - encodeStart} ms, ${temp.length() / 1024} KB")
            val uri = writer.saveJpeg(temp, existing)
            val updated = item.copy(mediaUri = uri, width = developed.width, height = developed.height)
            library.commit(updated)
            return updated
        } finally {
            temp.delete()
        }
    }

    /**
     * Shrinks by halves (each an exact 2 × 2 average) before the last step, so fine grain averages
     * out the way it does on a print instead of aliasing into sparkle.
     */
    private fun thumbnailOf(pixels: Pixels): Bitmap {
        var current = pixels.toBitmap()
        while (max(current.width, current.height) >= THUMB_EDGE * 2) {
            val half = Bitmap.createScaledBitmap(current, (current.width / 2).coerceAtLeast(1), (current.height / 2).coerceAtLeast(1), true)
            current.recycle()
            current = half
        }
        val scale = THUMB_EDGE.toFloat() / max(current.width, current.height)
        if (scale >= 1f) return current
        val thumb = Bitmap.createScaledBitmap(
            current,
            (current.width * scale).roundToInt().coerceAtLeast(1),
            (current.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        current.recycle()
        return thumb
    }

    /** Tightly packed, top-down RGBA8 pixels. */
    private class Pixels(val buffer: ByteBuffer, val width: Int, val height: Int) {
        fun toBitmap(): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            buffer.position(0)
            copyPixelsFromBuffer(buffer)
            buffer.position(0)
        }

        companion object {
            fun of(bitmap: Bitmap): Pixels {
                val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
                val buffer = ByteBuffer.allocateDirect(argb.width * argb.height * 4).order(ByteOrder.nativeOrder())
                argb.copyPixelsToBuffer(buffer)
                buffer.position(0)
                if (argb !== bitmap) argb.recycle()
                return Pixels(buffer, bitmap.width, bitmap.height)
            }
        }
    }

    private companion object {
        const val TAG = "KameraDarkroom"
        // Maximum quality, with full colour resolution: visually lossless even at 100 % zoom.
        const val NEGATIVE_QUALITY = 100
        const val FINAL_QUALITY = 100
        const val THUMB_QUALITY = 88
        const val THUMB_EDGE = 640
        const val MAX_IMPORT_EDGE = 8192
    }
}
