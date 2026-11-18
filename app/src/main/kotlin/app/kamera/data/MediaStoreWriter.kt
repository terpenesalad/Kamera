package app.kamera.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.system.Os
import android.util.Log
import app.kamera.film.Mp4Scrubber
import java.io.File
import java.io.FileDescriptor
import java.security.SecureRandom

/** Publishes finished pictures to `DCIM/Kamera` in the shared media collection. */
class MediaStoreWriter(context: Context) {
    private val resolver: ContentResolver = context.contentResolver

    /** A random name, so a shared file doesn't reveal when it was taken. */
    private fun privateName(extension: String): String {
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        return "KMR_" + bytes.joinToString("") { "%02x".format(it) } + extension
    }

    private fun baseValues(name: String, mime: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    /** Stores a JPEG, replacing [existing] in place when this app still owns that entry. */
    fun saveJpeg(file: File, existing: Uri?): Uri {
        if (existing != null) {
            val replaced = runCatching {
                resolver.openOutputStream(existing, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } != null
            }.getOrDefault(false)
            if (replaced) return existing
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, baseValues(privateName(".jpg"), "image/jpeg"))) {
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
    fun beginVideo(): PendingVideo {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, baseValues(privateName(".mp4"), "video/mp4"))) {
            "Could not create a video in shared storage"
        }
        val descriptor = requireNotNull(resolver.openFileDescriptor(uri, "rw")) { "Could not open the video file" }
        return PendingVideo(uri, descriptor)
    }

    /** Removes recording dates and device tags from the finished file, then publishes it. */
    fun finishVideo(video: PendingVideo) {
        runCatching { Mp4Scrubber.scrub(DescriptorBytes(video.descriptor.fileDescriptor)) }
            .onFailure { Log.w("KameraMedia", "Could not clean video metadata", it) }
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

    private class DescriptorBytes(private val fd: FileDescriptor) : Mp4Scrubber.Bytes {
        override val size: Long get() = Os.fstat(fd).st_size

        override fun read(position: Long, into: ByteArray, length: Int): Int = Os.pread(fd, into, 0, length, position)

        override fun write(position: Long, from: ByteArray, length: Int) {
            var written = 0
            while (written < length) written += Os.pwrite(fd, from, written, length - written, position + written)
        }
    }

    private companion object {
        const val FOLDER = "Kamera"
    }
}
