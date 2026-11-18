package app.kamera.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Publishes finished pictures to `DCIM/Kamera` in the shared media collection. */
class MediaStoreWriter(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    private fun stamp(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timeMs))

    private fun baseValues(name: String, mime: String, timeMs: Long) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.DATE_TAKEN, timeMs)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    /** Stores a JPEG, replacing [existing] in place when this app still owns that entry. */
    fun saveJpeg(file: File, timeMs: Long, existing: Uri?): Uri {
        if (existing != null) {
            val replaced = runCatching {
                resolver.openOutputStream(existing, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (replaced) return existing
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, baseValues("KMR_${stamp(timeMs)}.jpg", "image/jpeg", timeMs))) {
            "Could not create a photo in shared storage"
        }
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            markFinished(uri)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** Creates a hidden video entry that the muxer can write into. */
    fun beginVideo(timeMs: Long): PendingVideo {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, baseValues("KMR_${stamp(timeMs)}.mp4", "video/mp4", timeMs))) {
            "Could not create a video in shared storage"
        }
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "Could not open the video file" }
        return PendingVideo(uri, descriptor)
    }

    fun finishVideo(video: PendingVideo) {
        video.descriptor.close()
        markFinished(video.uri)
    }

    fun discardVideo(video: PendingVideo) {
        runCatching { video.descriptor.close() }
        runCatching { resolver.delete(video.uri, null, null) }
    }

    fun delete(uri: Uri) {
        runCatching { resolver.delete(uri, null, null) }
    }

    private fun markFinished(uri: Uri) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

    private companion object {
        const val FOLDER = "Kamera"
    }
}
