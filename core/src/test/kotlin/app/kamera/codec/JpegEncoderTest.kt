package app.kamera.codec

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import javax.imageio.ImageIO
import kotlin.math.log10

class JpegEncoderTest {

    /** A test card with gradients, hard edges, single-pixel colour detail and grain. */
    private fun card(width: Int, height: Int): ByteBuffer {
        val random = Random(7)
        val buf = ByteBuffer.allocate(width * height * 4)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = x * 255 / width
                var g = y * 255 / height
                var b = 128 + ((x - y) % 64)
                if (x in width / 3 until width / 2) {
                    // One-pixel red/blue stripes: lost entirely by 4:2:0 subsampling.
                    if (x % 2 == 0) { r = 220; g = 30; b = 40 } else { r = 30; g = 40; b = 220 }
                }
                val n = (random.nextGaussian() * 6).toInt()
                buf.put((r + n).coerceIn(0, 255).toByte())
                buf.put((g + n).coerceIn(0, 255).toByte())
                buf.put((b - n).coerceIn(0, 255).toByte())
                buf.put(0xFF.toByte())
            }
        }
        buf.flip()
        return buf
    }

    private fun encode(pixels: ByteBuffer, w: Int, h: Int, q: Int, threads: Int = 4, stripeRows: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        JpegEncoder.encode(pixels, w, h, q, out, threads, stripeRows)
        return out.toByteArray()
    }

    /** Peak signal-to-noise ratio of the decoded file against the source, per channel. */
    private fun psnr(jpeg: ByteArray, source: ByteBuffer, w: Int, h: Int, region: IntRange? = null): DoubleArray {
        val image = requireNotNull(ImageIO.read(ByteArrayInputStream(jpeg))) { "ImageIO could not decode the file" }
        assertEquals(w, image.width)
        assertEquals(h, image.height)
        val sums = DoubleArray(3)
        var count = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (region != null && x !in region) continue
                val rgb = image.getRGB(x, y)
                val decoded = intArrayOf((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                for (c in 0 until 3) {
                    val e = decoded[c] - (source.get((y * w + x) * 4 + c).toInt() and 0xFF)
                    sums[c] += (e * e).toDouble()
                }
                count++
            }
        }
        return DoubleArray(3) { 10 * log10(255.0 * 255.0 / (sums[it] / count).coerceAtLeast(1e-9)) }
    }

    @Test
    fun maximumQualityIsNearlyLossless() {
        val (w, h) = 333 to 217 // not multiples of 8, several stripes
        val pixels = card(w, h)
        val jpeg = encode(pixels, w, h, 100, stripeRows = 3)
        val p = psnr(jpeg, pixels, w, h)
        assertTrue("PSNR ${p.toList()}", p.all { it > 44.0 })
    }

    @Test
    fun keepsSinglePixelColourDetail() {
        val (w, h) = 240 to 96
        val pixels = card(w, h)
        val jpeg = encode(pixels, w, h, 100)
        val stripes = psnr(jpeg, pixels, w, h, (w / 3 + 8) until (w / 2 - 8))
        // 4:2:0 would average the red/blue pairs into purple, far below this.
        assertTrue("stripe PSNR ${stripes.toList()}", stripes.all { it > 38.0 })
    }

    @Test
    fun lowerQualityStillDecodesCleanly() {
        val (w, h) = 160 to 120
        val pixels = card(w, h)
        val jpeg = encode(pixels, w, h, 85)
        val p = psnr(jpeg, pixels, w, h)
        assertTrue("PSNR ${p.toList()}", p.all { it > 28.0 })
        assertTrue("q85 must be smaller than q100", jpeg.size < encode(pixels, w, h, 100).size)
    }

    @Test
    fun threadingDoesNotChangeTheFile() {
        val (w, h) = 200 to 150
        val pixels = card(w, h)
        assertArrayEquals(encode(pixels, w, h, 98, threads = 1, stripeRows = 2), encode(pixels, w, h, 98, threads = 6, stripeRows = 2))
    }

    @Test
    fun writesNoMetadata() {
        val (w, h) = 64 to 48
        val jpeg = encode(card(w, h), w, h, 100, stripeRows = 1)
        // Walk the marker segments before the scan.
        val markers = ArrayList<Int>()
        var i = 2
        while (i < jpeg.size) {
            assertEquals(0xFF, jpeg[i].toInt() and 0xFF)
            val marker = jpeg[i + 1].toInt() and 0xFF
            markers.add(marker)
            if (marker == 0xDA) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + length
        }
        assertEquals(listOf(0xE0, 0xDB, 0xC0, 0xC4, 0xDD, 0xDA), markers)
        val text = String(jpeg, Charsets.ISO_8859_1)
        assertTrue(!text.contains("Exif") && !text.contains("http") && !text.contains("ICC_PROFILE"))
        assertEquals(0xD9, jpeg.last().toInt() and 0xFF)
    }

    @Test
    fun huffmanTablesAreCompleteAndValid() {
        for (spec in listOf(JpegEncoder.Huffman.AC_LUMA, JpegEncoder.Huffman.AC_CHROMA)) {
            assertEquals(spec.counts.sum(), spec.values.size)
            val expected = (setOf(0x00, 0xF0) + (0..15).flatMap { r -> (1..10).map { s -> (r shl 4) or s } }).sorted()
            assertEquals(expected, spec.values.sorted())
        }
        for (spec in listOf(JpegEncoder.Huffman.DC_LUMA, JpegEncoder.Huffman.DC_CHROMA)) {
            assertEquals(12, spec.counts.sum())
        }
        // Kraft inequality with room left for the reserved all-ones code.
        for (spec in listOf(JpegEncoder.Huffman.DC_LUMA, JpegEncoder.Huffman.AC_LUMA, JpegEncoder.Huffman.DC_CHROMA, JpegEncoder.Huffman.AC_CHROMA)) {
            val kraft = spec.counts.withIndex().sumOf { (i, n) -> n / Math.pow(2.0, i + 1.0) }
            assertTrue(kraft < 1.0)
        }
    }

    @Test
    fun flatGreyIsExact() {
        val (w, h) = 48 to 40
        val buf = ByteBuffer.allocate(w * h * 4)
        repeat(w * h) { buf.put(118.toByte()); buf.put(118.toByte()); buf.put(118.toByte()); buf.put(-1) }
        buf.flip()
        val p = psnr(encode(buf, w, h, 100), buf, w, h)
        assertTrue("PSNR ${p.toList()}", p.all { it > 60 })
    }
}
