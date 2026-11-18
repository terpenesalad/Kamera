package app.kamera.film

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Still-photo formats; the aspect is always long edge over short edge. */
enum class PhotoFormat(val id: String, val label: String, val detail: String, val aspect: Float) {
    FULL_FRAME("3:2", "3:2", "35 mm", 3f / 2f),
    MEDIUM_645("4:3", "4:3", "645", 4f / 3f),
    SQUARE("1:1", "1:1", "6×6", 1f),
    LARGE_45("5:4", "5:4", "4×5", 5f / 4f),
    WIDE("16:9", "16:9", "Wide", 16f / 9f),
    PANORAMA("65:24", "65:24", "Panorama", 65f / 24f);

    companion object {
        fun fromId(id: String?): PhotoFormat = entries.firstOrNull { it.id == id } ?: FULL_FRAME
    }
}

/** Motion-picture frame shapes, recorded inside a 16:9 sensor readout. */
enum class VideoFormat(val id: String, val label: String, val aspect: Float) {
    HD("16:9", "16:9", 16f / 9f),
    FLAT("1.85", "1.85", 1.85f),
    SCOPE("2.39", "2.39", 2.39f);

    companion object {
        fun fromId(id: String?): VideoFormat = entries.firstOrNull { it.id == id } ?: HD
    }
}

/**
 * A 2D affine map from frame UV to source UV:
 * `u' = a·u + b·v + c`, `v' = d·u + e·v + f`.
 */
data class Affine(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float) {
    fun mapU(u: Float, v: Float): Float = a * u + b * v + c
    fun mapV(u: Float, v: Float): Float = d * u + e * v + f

    /** `this ∘ inner`: applies [inner] first. */
    operator fun times(inner: Affine): Affine = Affine(
        a * inner.a + b * inner.d,
        a * inner.b + b * inner.e,
        a * inner.c + b * inner.f + c,
        d * inner.a + e * inner.d,
        d * inner.b + e * inner.e,
        d * inner.c + e * inner.f + f,
    )

    /** Column-major 4×4 for `glUniformMatrix4fv`. */
    fun toMat4(): FloatArray = floatArrayOf(
        a, d, 0f, 0f,
        b, e, 0f, 0f,
        0f, 0f, 1f, 0f,
        c, f, 0f, 1f,
    )

    companion object {
        val IDENTITY = Affine(1f, 0f, 0f, 0f, 1f, 0f)

        /** Reads the affine part of a column-major 4×4, such as `SurfaceTexture.getTransformMatrix`. */
        fun fromMat4(m: FloatArray): Affine = Affine(m[0], m[4], m[12], m[1], m[5], m[13])

        /** Bitmaps store their top row first, while frame UV has its origin at the bottom left. */
        val FLIP_V = Affine(1f, 0f, 0f, 0f, -1f, 1f)
    }
}

/** Pure framing maths shared by the viewfinder, the developer and the editor. */
object FrameGeometry {
    /**
     * Maps output-frame UV to upright source UV, applying crop, rotation and mirroring.
     *
     * @param sourceAspect width / height of the upright source
     * @param frameAspect  width / height of the output frame
     */
    fun frameToSource(sourceAspect: Float, frameAspect: Float, framing: Framing = Framing()): Affine {
        val theta = Math.toRadians(framing.straighten.toDouble() + framing.quarterTurns * 90.0)
        val cosT = cos(theta).toFloat()
        val sinT = sin(theta).toFloat()

        // Height of the frame measured in source heights, at the widest crop that stays inside.
        val fit = min(
            sourceAspect / (frameAspect * abs(cosT) + abs(sinT)),
            1f / (frameAspect * abs(sinT) + abs(cosT)),
        )
        val height = fit / max(framing.zoom, 1f)

        // Remaining travel after the rotated frame's bounding box is placed inside the source.
        val boxW = height * (frameAspect * abs(cosT) + abs(sinT))
        val boxH = height * (frameAspect * abs(sinT) + abs(cosT))
        val travelX = max(sourceAspect - boxW, 0f) * 0.5f
        val travelY = max(1f - boxH, 0f) * 0.5f
        val cx = 0.5f + framing.panX.coerceIn(-1f, 1f) * travelX / sourceAspect
        val cy = 0.5f + framing.panY.coerceIn(-1f, 1f) * travelY

        val flip = if (framing.flipHorizontal) -1f else 1f

        // frame UV → centred, height-normalised → rotated → source UV.
        val sx = frameAspect * height * flip
        val a = sx * cosT / sourceAspect
        val b = -height * sinT / sourceAspect
        val d = sx * sinT
        val e = height * cosT
        return Affine(a, b, cx - 0.5f * (a + b), d, e, cy - 0.5f * (d + e))
    }

    /** Aspect of the output frame once quarter turns are taken into account. */
    fun outputAspect(sourceAspect: Float, framing: Framing): Float {
        val turned = framing.quarterTurns.mod(2) == 1
        val upright = if (turned) 1f / sourceAspect else sourceAspect
        if (framing.aspect <= 0f) return upright
        // A format is stored long-edge-first; follow the orientation of the upright picture.
        val long = max(framing.aspect, 1f / framing.aspect)
        return if (upright >= 1f) long else 1f / long
    }

    /** Pixel size of the developed frame, preserving source resolution and even dimensions. */
    fun outputSize(sourceWidth: Int, sourceHeight: Int, framing: Framing): Pair<Int, Int> {
        val sourceAspect = sourceWidth.toFloat() / sourceHeight
        val aspect = outputAspect(sourceAspect, framing)
        val theta = Math.toRadians(framing.straighten.toDouble() + framing.quarterTurns * 90.0)
        val cosT = abs(cos(theta)).toFloat()
        val sinT = abs(sin(theta)).toFloat()
        val fit = min(sourceAspect / (aspect * cosT + sinT), 1f / (aspect * sinT + cosT))
        val height = (sourceHeight * fit / max(framing.zoom, 1f)).roundToInt().coerceAtLeast(2)
        val width = (height * aspect).roundToInt().coerceAtLeast(2)
        return (width and 1.inv()) to (height and 1.inv())
    }

    /** Largest rectangle of [frameAspect] centred inside a [containerW] × [containerH] area. */
    fun fit(containerW: Int, containerH: Int, frameAspect: Float): Pair<Int, Int> {
        val containerAspect = containerW.toFloat() / containerH
        return if (frameAspect > containerAspect) {
            containerW to (containerW / frameAspect).roundToInt()
        } else {
            (containerH * frameAspect).roundToInt() to containerH
        }
    }
}

/**
 * Geometry of camera buffers. A still arrives in sensor orientation; the negative is stored upright
 * and, for front cameras, mirrored exactly as the viewfinder showed it.
 */
object CaptureGeometry {
    /** Maps upright display UV (v up) to buffer UV (v down, row 0 at v = 0). */
    fun displayToBuffer(bufferWidth: Int, bufferHeight: Int, sensorDegrees: Int, frontFacing: Boolean): Affine {
        val framing = Framing(quarterTurns = sensorDegrees.mod(360) / 90, flipHorizontal = frontFacing)
        val bufferAspect = bufferWidth.toFloat() / bufferHeight
        val displayAspect = FrameGeometry.outputAspect(bufferAspect, framing)
        return Affine.FLIP_V * FrameGeometry.frameToSource(bufferAspect, displayAspect, framing)
    }

    /** Pixel size of the upright negative for a device held at [deviceDegrees]. */
    fun negativeSize(bufferWidth: Int, bufferHeight: Int, sensorDegrees: Int, deviceDegrees: Int): Pair<Int, Int> {
        val turned = (sensorDegrees.mod(360) / 90 + deviceDegrees.mod(360) / 90).mod(2) == 1
        return if (turned) bufferHeight to bufferWidth else bufferWidth to bufferHeight
    }

    /** Maps negative UV to buffer UV, turning the picture upright for the device's orientation. */
    fun negativeToBuffer(
        bufferWidth: Int,
        bufferHeight: Int,
        sensorDegrees: Int,
        frontFacing: Boolean,
        deviceDegrees: Int,
    ): Affine {
        val displayOdd = sensorDegrees.mod(360) / 90 % 2 == 1
        val displayAspect = if (displayOdd) bufferHeight.toFloat() / bufferWidth else bufferWidth.toFloat() / bufferHeight
        val framing = Framing(quarterTurns = deviceDegrees.mod(360) / 90)
        val negativeAspect = FrameGeometry.outputAspect(displayAspect, framing)
        return displayToBuffer(bufferWidth, bufferHeight, sensorDegrees, frontFacing) *
            FrameGeometry.frameToSource(displayAspect, negativeAspect, framing)
    }

    /** Snaps a raw orientation sensor reading to the nearest quarter turn. */
    fun snapDegrees(degrees: Int): Int = (((degrees + 45).mod(360)) / 90 * 90).mod(360)
}
