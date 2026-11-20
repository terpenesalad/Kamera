package app.kamera.codec

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.max
import kotlin.math.min

/**
 * Baseline JPEG encoder that keeps full colour resolution (4:4:4, no chroma subsampling).
 *
 * Android's built-in encoder always halves colour resolution, which smears fine colour grain into
 * 2 × 2 blocks. This one encodes every pixel's colour. The file carries only what a decoder needs:
 * no EXIF, no location, no device, no dates, no comments.
 *
 * The picture is cut into horizontal stripes joined by restart markers, so stripes encode in
 * parallel and the result is still one standard JPEG.
 */
object JpegEncoder {

    /**
     * Encodes tightly packed, top-down RGBA8 [pixels] (alpha ignored).
     * [quality] follows the usual 1–100 scale; 100 sets every quantiser step to 1.
     */
    fun encode(
        pixels: ByteBuffer,
        width: Int,
        height: Int,
        quality: Int,
        out: OutputStream,
        threads: Int = Runtime.getRuntime().availableProcessors(),
        stripeMcuRows: Int = 0,
    ) {
        require(width in 1..65535 && height in 1..65535) { "Unsupported size ${width}x$height" }
        require(pixels.capacity() >= width * height * 4) { "Pixel buffer too small" }

        val tables = Tables(quality.coerceIn(1, 100))
        val mcuCols = (width + 7) / 8
        val mcuRows = (height + 7) / 8
        val rowsPerStripe = when {
            stripeMcuRows > 0 -> stripeMcuRows
            else -> max(1, min(DEFAULT_STRIPE_MCU_ROWS, 65535 / mcuCols))
        }.coerceAtMost(max(1, 65535 / mcuCols))
        val stripes = (mcuRows + rowsPerStripe - 1) / rowsPerStripe
        val restartInterval = mcuCols * rowsPerStripe

        val encoded = arrayOfNulls<ByteArray>(stripes)
        val workers = threads.coerceIn(1, stripes)
        if (workers == 1) {
            for (s in 0 until stripes) encoded[s] = encodeStripe(pixels, width, height, s * rowsPerStripe, rowsPerStripe, tables)
        } else {
            val pool = Executors.newFixedThreadPool(workers) { r -> Thread(r, "JpegStripe").apply { isDaemon = true } }
            try {
                val futures: List<Future<ByteArray>> = (0 until stripes).map { s ->
                    pool.submit(Callable { encodeStripe(pixels, width, height, s * rowsPerStripe, rowsPerStripe, tables) })
                }
                futures.forEachIndexed { s, f -> encoded[s] = f.get() }
            } finally {
                pool.shutdownNow()
            }
        }

        writeHeaders(out, width, height, tables, if (stripes > 1) restartInterval else 0)
        for (s in 0 until stripes) {
            out.write(encoded[s]!!)
            if (s < stripes - 1) {
                out.write(0xFF)
                out.write(0xD0 + (s and 7))
            }
        }
        out.write(0xFF)
        out.write(0xD9)
        out.flush()
    }

    private const val DEFAULT_STRIPE_MCU_ROWS = 8

    // ---------------------------------------------------------------------------------------------
    // Entropy-coded data

    private fun encodeStripe(
        source: ByteBuffer,
        width: Int,
        height: Int,
        firstMcuRow: Int,
        mcuRowCount: Int,
        tables: Tables,
    ): ByteArray {
        val y0 = firstMcuRow * 8
        if (y0 >= height) return ByteArray(0)
        val y1 = min(height, (firstMcuRow + mcuRowCount) * 8)
        val rows = y1 - y0
        val stride = width * 4

        // Copy this stripe's rows so the shared buffer is only read in bulk.
        val rgba = ByteArray(rows * stride)
        val view = source.duplicate()
        view.position(y0 * stride)
        view.get(rgba, 0, rgba.size)

        val sink = BitSink(max(4096, rows * width * 2))
        val block = FloatArray(64)
        val zz = IntArray(64)
        val lastDc = IntArray(3)
        val mcuCols = (width + 7) / 8
        val blockRows = (rows + 7) / 8

        for (by in 0 until blockRows) {
            for (bx in 0 until mcuCols) {
                for (c in 0 until 3) {
                    loadBlock(rgba, width, rows, bx * 8, by * 8, c, block)
                    fdct(block)
                    val divisors = if (c == 0) tables.lumaDivisors else tables.chromaDivisors
                    for (k in 0 until 64) {
                        val natural = ZIGZAG[k]
                        val v = block[natural] * divisors[natural]
                        val q = (v + 16384.5f).toInt() - 16384
                        zz[k] = if (k == 0) q.coerceIn(-2047, 2047) else q.coerceIn(-1023, 1023)
                    }
                    val dc = if (c == 0) tables.dcLuma else tables.dcChroma
                    val ac = if (c == 0) tables.acLuma else tables.acChroma
                    lastDc[c] = encodeBlock(sink, zz, lastDc[c], dc, ac)
                }
            }
        }
        sink.padToByte()
        return sink.toByteArray()
    }

    /** Fills [block] with one level-shifted 8 × 8 component, repeating edge pixels past the border. */
    private fun loadBlock(rgba: ByteArray, width: Int, rows: Int, x0: Int, y0: Int, component: Int, block: FloatArray) {
        var i = 0
        for (dy in 0 until 8) {
            val y = min(y0 + dy, rows - 1)
            val rowBase = y * width * 4
            for (dx in 0 until 8) {
                val x = min(x0 + dx, width - 1)
                val p = rowBase + x * 4
                val r = (rgba[p].toInt() and 0xFF).toFloat()
                val g = (rgba[p + 1].toInt() and 0xFF).toFloat()
                val b = (rgba[p + 2].toInt() and 0xFF).toFloat()
                block[i++] = when (component) {
                    0 -> 0.299f * r + 0.587f * g + 0.114f * b - 128f
                    1 -> -0.168736f * r - 0.331264f * g + 0.5f * b
                    else -> 0.5f * r - 0.418688f * g - 0.081312f * b
                }
            }
        }
    }

    private fun encodeBlock(sink: BitSink, zz: IntArray, previousDc: Int, dc: Huffman, ac: Huffman): Int {
        val diff = zz[0] - previousDc
        val dcSize = magnitudeBits(diff)
        sink.put(dc.codes[dcSize], dc.lengths[dcSize])
        if (dcSize > 0) sink.put(valueBits(diff, dcSize), dcSize)

        var run = 0
        for (k in 1 until 64) {
            val v = zz[k]
            if (v == 0) {
                run++
                continue
            }
            while (run > 15) {
                sink.put(ac.codes[0xF0], ac.lengths[0xF0])
                run -= 16
            }
            val size = magnitudeBits(v)
            val symbol = (run shl 4) or size
            sink.put(ac.codes[symbol], ac.lengths[symbol])
            sink.put(valueBits(v, size), size)
            run = 0
        }
        if (run > 0) sink.put(ac.codes[0x00], ac.lengths[0x00])
        return zz[0]
    }

    private fun magnitudeBits(v: Int): Int = if (v == 0) 0 else 32 - Integer.numberOfLeadingZeros(if (v < 0) -v else v)

    private fun valueBits(v: Int, size: Int): Int = (if (v < 0) v - 1 else v) and ((1 shl size) - 1)

    /** In-place forward DCT (AAN, floating point); outputs are scaled, see [Tables]. */
    private fun fdct(d: FloatArray) {
        for (pass in 0 until 2) {
            val step = if (pass == 0) 1 else 8
            val stride = if (pass == 0) 8 else 1
            for (line in 0 until 8) {
                val o = line * stride
                val i0 = o; val i1 = o + step; val i2 = o + 2 * step; val i3 = o + 3 * step
                val i4 = o + 4 * step; val i5 = o + 5 * step; val i6 = o + 6 * step; val i7 = o + 7 * step
                val tmp0 = d[i0] + d[i7]
                val tmp7 = d[i0] - d[i7]
                val tmp1 = d[i1] + d[i6]
                val tmp6 = d[i1] - d[i6]
                val tmp2 = d[i2] + d[i5]
                val tmp5 = d[i2] - d[i5]
                val tmp3 = d[i3] + d[i4]
                val tmp4 = d[i3] - d[i4]

                var tmp10 = tmp0 + tmp3
                val tmp13 = tmp0 - tmp3
                var tmp11 = tmp1 + tmp2
                var tmp12 = tmp1 - tmp2
                d[i0] = tmp10 + tmp11
                d[i4] = tmp10 - tmp11
                val z1 = (tmp12 + tmp13) * 0.707106781f
                d[i2] = tmp13 + z1
                d[i6] = tmp13 - z1

                tmp10 = tmp4 + tmp5
                tmp11 = tmp5 + tmp6
                tmp12 = tmp6 + tmp7
                val z5 = (tmp10 - tmp12) * 0.382683433f
                val z2 = 0.541196100f * tmp10 + z5
                val z4 = 1.306562965f * tmp12 + z5
                val z3 = tmp11 * 0.707106781f
                val z11 = tmp7 + z3
                val z13 = tmp7 - z3
                d[i5] = z13 + z2
                d[i3] = z13 - z2
                d[i1] = z11 + z4
                d[i7] = z11 - z4
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Headers

    private fun writeHeaders(out: OutputStream, width: Int, height: Int, t: Tables, restartInterval: Int) {
        val h = ByteArrayOutputStream(1024)
        fun u8(v: Int) = h.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v ushr 8)
            u8(v)
        }

        u16(0xFFD8) // SOI
        // JFIF: version and a 1:1 pixel aspect, nothing else.
        u16(0xFFE0); u16(16)
        "JFIF".forEach { u8(it.code) }; u8(0); u8(1); u8(2); u8(0); u16(1); u16(1); u8(0); u8(0)

        u16(0xFFDB); u16(2 + 2 * 65)
        u8(0x00); for (k in 0 until 64) u8(t.lumaQuant[ZIGZAG[k]])
        u8(0x01); for (k in 0 until 64) u8(t.chromaQuant[ZIGZAG[k]])

        // Baseline, 8-bit, three components at full resolution (sampling factors 1 × 1).
        u16(0xFFC0); u16(17); u8(8); u16(height); u16(width); u8(3)
        u8(1); u8(0x11); u8(0)
        u8(2); u8(0x11); u8(1)
        u8(3); u8(0x11); u8(1)

        val specs = listOf(0x00 to Huffman.DC_LUMA, 0x10 to Huffman.AC_LUMA, 0x01 to Huffman.DC_CHROMA, 0x11 to Huffman.AC_CHROMA)
        u16(0xFFC4); u16(2 + specs.sumOf { 17 + it.second.values.size })
        for ((id, spec) in specs) {
            u8(id)
            spec.counts.forEach { u8(it) }
            spec.values.forEach { u8(it) }
        }

        if (restartInterval > 0) {
            u16(0xFFDD); u16(4); u16(restartInterval)
        }

        u16(0xFFDA); u16(12); u8(3)
        u8(1); u8(0x00)
        u8(2); u8(0x11)
        u8(3); u8(0x11)
        u8(0); u8(63); u8(0)
        h.writeTo(out)
    }

    // ---------------------------------------------------------------------------------------------
    // Tables

    internal class Tables(quality: Int) {
        val lumaQuant = scale(BASE_LUMA, quality)
        val chromaQuant = scale(BASE_CHROMA, quality)
        val lumaDivisors = divisors(lumaQuant)
        val chromaDivisors = divisors(chromaQuant)
        val dcLuma = Huffman(Huffman.DC_LUMA)
        val acLuma = Huffman(Huffman.AC_LUMA)
        val dcChroma = Huffman(Huffman.DC_CHROMA)
        val acChroma = Huffman(Huffman.AC_CHROMA)

        private fun scale(base: IntArray, quality: Int): IntArray {
            val s = if (quality < 50) 5000 / quality else 200 - quality * 2
            return IntArray(64) { ((base[it] * s + 50) / 100).coerceIn(1, 255) }
        }

        /** Folds the AAN output scaling into the quantiser, as libjpeg's float path does. */
        private fun divisors(q: IntArray): FloatArray = FloatArray(64) {
            val row = it / 8
            val col = it % 8
            (1.0 / (q[it] * AAN_SCALE[row] * AAN_SCALE[col] * 8.0)).toFloat()
        }
    }

    internal class Spec(val counts: IntArray, val values: IntArray)

    internal class Huffman(spec: Spec) {
        val codes = IntArray(256)
        val lengths = IntArray(256)

        init {
            var code = 0
            var k = 0
            for (len in 1..16) {
                repeat(spec.counts[len - 1]) {
                    val symbol = spec.values[k++]
                    codes[symbol] = code
                    lengths[symbol] = len
                    code++
                }
                code = code shl 1
            }
        }

        companion object {
            val DC_LUMA = Spec(
                intArrayOf(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0),
                IntArray(12) { it },
            )
            val DC_CHROMA = Spec(
                intArrayOf(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0),
                IntArray(12) { it },
            )
            val AC_LUMA = Spec(
                intArrayOf(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D),
                intArrayOf(
                    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
                    0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08, 0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0,
                    0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
                    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
                    0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
                    0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
                    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7,
                    0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5,
                    0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
                    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
                    0xF9, 0xFA,
                ),
            )
            val AC_CHROMA = Spec(
                intArrayOf(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77),
                intArrayOf(
                    0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
                    0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xA1, 0xB1, 0xC1, 0x09, 0x23, 0x33, 0x52, 0xF0,
                    0x15, 0x62, 0x72, 0xD1, 0x0A, 0x16, 0x24, 0x34, 0xE1, 0x25, 0xF1, 0x17, 0x18, 0x19, 0x1A, 0x26,
                    0x27, 0x28, 0x29, 0x2A, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
                    0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
                    0x69, 0x6A, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
                    0x88, 0x89, 0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3, 0xA4, 0xA5,
                    0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6, 0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3,
                    0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA,
                    0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8,
                    0xF9, 0xFA,
                ),
            )
        }
    }

    /** Big-endian bit writer with JPEG byte stuffing (0xFF is always followed by 0x00). */
    private class BitSink(capacity: Int) {
        private var buffer = ByteArray(capacity)
        private var size = 0
        private var acc = 0
        private var bits = 0

        fun put(value: Int, length: Int) {
            if (length == 0) return
            acc = (acc shl length) or (value and ((1 shl length) - 1))
            bits += length
            while (bits >= 8) {
                bits -= 8
                val byte = (acc ushr bits) and 0xFF
                write(byte)
                if (byte == 0xFF) write(0)
            }
        }

        /** Finishes a segment by filling the last byte with 1-bits, as the standard asks. */
        fun padToByte() {
            if (bits > 0) put((1 shl (8 - bits)) - 1, 8 - bits)
            acc = 0
        }

        private fun write(byte: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = byte.toByte()
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    internal val ZIGZAG = intArrayOf(
        0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
        12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
        35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
        58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
    )

    private val AAN_SCALE = doubleArrayOf(
        1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379,
    )

    private val BASE_LUMA = intArrayOf(
        16, 11, 10, 16, 24, 40, 51, 61,
        12, 12, 14, 19, 26, 58, 60, 55,
        14, 13, 16, 24, 40, 57, 69, 56,
        14, 17, 22, 29, 51, 87, 80, 62,
        18, 22, 37, 56, 68, 109, 103, 77,
        24, 35, 55, 64, 81, 104, 113, 92,
        49, 64, 78, 87, 103, 121, 120, 101,
        72, 92, 95, 98, 112, 100, 103, 99,
    )

    private val BASE_CHROMA = intArrayOf(
        17, 18, 24, 47, 99, 99, 99, 99,
        18, 21, 26, 66, 99, 99, 99, 99,
        24, 26, 56, 99, 99, 99, 99, 99,
        47, 66, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
        99, 99, 99, 99, 99, 99, 99, 99,
    )
}
