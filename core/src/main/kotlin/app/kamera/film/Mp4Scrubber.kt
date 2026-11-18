package app.kamera.film

/**
 * Removes private details from a finished MP4 in place.
 *
 * Android's muxer stamps the recording date into the movie, track and media headers and adds a
 * device tag box. Dates are zeroed and tag boxes are turned into padding, so the file length and all
 * audio and video data stay exactly as they were.
 */
object Mp4Scrubber {
    interface Bytes {
        val size: Long
        fun read(position: Long, into: ByteArray, length: Int): Int
        fun write(position: Long, from: ByteArray, length: Int)
    }

    fun scrub(file: Bytes) = walk(file, 0L, file.size)

    private fun walk(file: Bytes, start: Long, end: Long) {
        val header = ByteArray(16)
        var position = start
        while (position + 8 <= end) {
            if (!readFully(file, position, header, 8)) return
            var boxSize = int32(header, 0)
            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            var headerSize = 8
            if (boxSize == 1L) {
                if (!readFully(file, position, header, 16)) return
                boxSize = (int32(header, 8) shl 32) or int32(header, 12)
                headerSize = 16
            } else if (boxSize == 0L) {
                boxSize = end - position
            }
            if (boxSize < headerSize || position + boxSize > end) return
            val body = position + headerSize
            when (type) {
                "moov", "trak", "mdia" -> walk(file, body, position + boxSize)
                "mvhd", "tkhd", "mdhd" -> clearDates(file, body)
                "udta", "meta" -> file.write(position + 4, FREE, FREE.size)
            }
            position += boxSize
        }
    }

    /** Full boxes hold created and modified dates after the version and flags. */
    private fun clearDates(file: Bytes, body: Long) {
        val version = ByteArray(1)
        if (!readFully(file, body, version, 1)) return
        val length = if (version[0].toInt() == 1) 16 else 8
        file.write(body + 4, ByteArray(length), length)
    }

    private fun readFully(file: Bytes, position: Long, into: ByteArray, length: Int): Boolean {
        var total = 0
        val chunk = ByteArray(length)
        while (total < length) {
            val read = file.read(position + total, chunk, length - total)
            if (read <= 0) return false
            System.arraycopy(chunk, 0, into, total, read)
            total += read
        }
        return true
    }

    private fun int32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)

    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)
}
