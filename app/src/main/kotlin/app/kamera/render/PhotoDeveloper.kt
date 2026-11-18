package app.kamera.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import app.kamera.camera.StillCapture
import app.kamera.camera.StillMeta
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        val created = System.currentTimeMillis()
        val placeholder = library.newItem(MediaKind.PHOTO, negW, negH, 0L, null, filmBaked = false)
        try {
            val negativeBitmap = readTexture(film, negative)
            writeJpeg(negativeBitmap, placeholder.negative, NEGATIVE_QUALITY)
            negativeBitmap.recycle()
            placeholder.writeRecipe(recipe)
            val developed = developTexture(film, negative.texture, negW, negH, flippedV = false, recipe)
            val item = publish(placeholder, developed, recipe, created, still.meta, existing = null)
            developed.recycle()
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
                val updated = publish(item, developed, recipe, item.createdMs, null, existing = item.mediaUri)
                developed.recycle()
                updated
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
                writeJpeg(bitmap, item.negative, NEGATIVE_QUALITY)
                item.writeRecipe(recipe)
                val developed = developBitmap(film, bitmap, recipe)
                bitmap.recycle()
                val published = publish(item, developed, recipe, item.createdMs, null, existing = null)
                developed.recycle()
                published
            }.onFailure { Log.e(TAG, "Import failed", it) }.getOrNull()
            main.post { onDone(result) }
        }
    }

    private fun developBitmap(film: FilmEngine, negative: Bitmap, recipe: Recipe): Bitmap {
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
    ): Bitmap {
        val stock = FilmCatalog.find(recipe.stockId)
        film.uploadLut(LutStore.get(stock, recipe.grade))
        val (outW, outH) = FrameGeometry.outputSize(width, height, recipe.framing)
        val aspect = outW.toFloat() / outH
        val map = FrameGeometry.frameToSource(width.toFloat() / height, aspect, recipe.framing)
        val source = SourceFrame(texture, false, width, height, if (flippedV) Affine.FLIP_V * map else map)
        val uniforms = FilmUniforms.compute(stock, recipe, outW, outH)
        film.updateGlow(glow, source, uniforms, aspect)
        val target = RenderTarget(outW, outH)
        film.drawFilm(source, glow, uniforms, OutputTarget(target.framebuffer, 0, 0, outW, outH, flipY = true))
        val bitmap = readPixels(target.framebuffer, outW, outH)
        target.release()
        glow.release()
        return bitmap
    }

    /** Reads a bottom-up render target as a normal top-down bitmap. */
    private fun readTexture(film: FilmEngine, source: RenderTarget): Bitmap {
        val flipped = RenderTarget(source.width, source.height)
        film.drawOriginal(
            SourceFrame(source.texture, false, source.width, source.height, Affine.IDENTITY),
            OutputTarget(flipped.framebuffer, 0, 0, source.width, source.height, flipY = true),
        )
        val bitmap = readPixels(flipped.framebuffer, source.width, source.height)
        flipped.release()
        return bitmap
    }

    private fun readPixels(framebuffer: Int, width: Int, height: Int): Bitmap {
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        pixels.position(0)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(pixels) }
    }

    private fun writeJpeg(bitmap: Bitmap, file: File, quality: Int) {
        file.outputStream().buffered().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    private fun publish(
        item: LibraryItem,
        developed: Bitmap,
        recipe: Recipe,
        takenMs: Long,
        meta: StillMeta?,
        existing: Uri?,
    ): LibraryItem {
        val scale = THUMB_EDGE.toFloat() / max(developed.width, developed.height)
        val thumb = Bitmap.createScaledBitmap(
            developed,
            (developed.width * scale).roundToInt().coerceAtLeast(1),
            (developed.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
        writeJpeg(thumb, item.thumbnail, THUMB_QUALITY)
        thumb.recycle()

        val temp = File.createTempFile("develop", ".jpg", context.cacheDir)
        try {
            writeJpeg(developed, temp, FINAL_QUALITY)
            writeExif(temp, recipe, takenMs, meta)
            val uri = writer.saveJpeg(temp, takenMs, existing)
            val updated = item.copy(mediaUri = uri, width = developed.width, height = developed.height)
            library.commit(updated)
            return updated
        } finally {
            temp.delete()
        }
    }

    private fun writeExif(file: File, recipe: Recipe, takenMs: Long, meta: StillMeta?) {
        val stock = FilmCatalog.find(recipe.stockId)
        val exif = ExifInterface(file.path)
        val date = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(takenMs))
        exif.setAttribute(ExifInterface.TAG_DATETIME, date)
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, date)
        exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
        exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Kamera")
        exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, "${stock.name} (ISO ${stock.iso})")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        if (meta != null) {
            exif.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, meta.iso.toString())
            exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "${meta.shutterNs / 1000L}/1000000")
            exif.setAttribute(ExifInterface.TAG_F_NUMBER, "${(meta.aperture * 100).roundToInt()}/100")
            exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${(meta.focalLength * 100).roundToInt()}/100")
        }
        exif.saveAttributes()
    }

    private companion object {
        const val TAG = "KameraDarkroom"
        const val NEGATIVE_QUALITY = 96
        const val FINAL_QUALITY = 95
        const val THUMB_QUALITY = 86
        const val THUMB_EDGE = 640
        const val MAX_IMPORT_EDGE = 8192
    }
}
