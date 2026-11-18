package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class Mp4ScrubberTest {
    private class Memory(val data: ByteArray) : Mp4Scrubber.Bytes {
        override val size: Long get() = data.size.toLong()

        override fun read(position: Long, into: ByteArray, length: Int): Int {
            val count = minOf(length.toLong(), size - position).toInt()
            if (count <= 0) return -1
            System.arraycopy(data, position.toInt(), into, 0, count)
            return count
        }

        override fun write(position: Long, from: ByteArray, length: Int) {
            System.arraycopy(from, 0, data, position.toInt(), length)
        }
    }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()
    }

    private val stamp = ByteBuffer.allocate(4).putInt(0x7A7A7A7A).array()
    private fun header32() = byteArrayOf(0, 0, 0, 0) + stamp + stamp + ByteArray(12) { 5 }
    private fun header64() = byteArrayOf(1, 0, 0, 0) + stamp + stamp + stamp + stamp + ByteArray(12) { 5 }

    @Test
    fun datesAndDeviceTagsAreRemovedWithoutTouchingMedia() {
        val media = ByteArray(64) { 9 }
        val file = box("ftyp", ByteArray(8) { 1 }) + box("mdat", media) + box(
            "moov",
            box("mvhd", header32()),
            box("trak", box("tkhd", header64()), box("mdia", box("mdhd", header32()))),
            box("udta", "Pixel 9 2026".toByteArray()),
            box("meta", "com.android.version".toByteArray()),
        )
        val memory = Memory(file.copyOf())
        Mp4Scrubber.scrub(memory)

        val text = String(memory.data, Charsets.ISO_8859_1)
        assertEquals(file.size, memory.data.size)
        assertTrue("dates cleared", !text.contains("zzzz"))
        assertTrue("tag boxes neutralised", !text.contains("udta") && !text.contains("meta"))
        assertTrue("media untouched", text.contains(String(media, Charsets.ISO_8859_1)))
    }

    @Test
    fun malformedFilesAreLeftAlone() {
        val junk = ByteArray(40) { 0x7F }
        val memory = Memory(junk.copyOf())
        Mp4Scrubber.scrub(memory)
        assertTrue(junk.contentEquals(memory.data))
    }
}
