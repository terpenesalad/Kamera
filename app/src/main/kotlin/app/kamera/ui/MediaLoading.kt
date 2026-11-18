package app.kamera.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

/** Creates poster frames for video clips. */
object VideoThumbnails {
    fun write(context: Context, uri: Uri, file: File) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return
                val scale = 640f / max(frame.width, frame.height)
                val scaled = Bitmap.createScaledBitmap(frame, (frame.width * scale).roundToInt(), (frame.height * scale).roundToInt(), true)
                file.outputStream().buffered().use { scaled.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            } finally {
                retriever.release()
            }
        }
    }
}

/** Loads contact-sheet thumbnails off the main thread, with a small memory cache. */
object ThumbnailLoader {
    private val cache = LruCache<String, Bitmap>(120)
    private val executor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(file: File, into: ImageView) {
        val key = file.path + file.lastModified()
        into.tag = key
        val cached = cache.get(key)
        if (cached != null) {
            into.setImageBitmap(cached)
            return
        }
        into.setImageDrawable(null)
        executor.execute {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return@execute
            cache.put(key, bitmap)
            main.post { if (into.tag == key) into.setImageBitmap(bitmap) }
        }
    }
}

/** Decodes a picture no larger than [maxEdge] on its longest side. */
fun decodeBounded(open: () -> java.io.InputStream?, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0) return null
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    return open()?.use { BitmapFactory.decodeStream(it, null, options) }
}
