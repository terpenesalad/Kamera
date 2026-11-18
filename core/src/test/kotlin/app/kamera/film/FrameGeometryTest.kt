package app.kamera.film

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGeometryTest {
    private fun inside(m: Affine) {
        for ((u, v) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            val su = m.mapU(u, v)
            val sv = m.mapV(u, v)
            assertTrue("corner $u,$v maps to $su,$sv", su in -0.0005f..1.0005f && sv in -0.0005f..1.0005f)
        }
    }

    @Test
    fun matchingAspectIsIdentity() {
        val m = FrameGeometry.frameToSource(1.5f, 1.5f)
        assertEquals(0f, m.mapU(0f, 0f), 1e-4f)
        assertEquals(1f, m.mapU(1f, 1f), 1e-4f)
        assertEquals(1f, m.mapV(1f, 1f), 1e-4f)
    }

    @Test
    fun widerFrameCropsTopAndBottom() {
        val m = FrameGeometry.frameToSource(4f / 3f, 16f / 9f)
        assertEquals(0f, m.mapU(0f, 0.5f), 1e-4f)
        assertEquals(1f, m.mapU(1f, 0.5f), 1e-4f)
        assertEquals(0.125f, m.mapV(0.5f, 0f), 1e-4f)
        assertEquals(0.875f, m.mapV(0.5f, 1f), 1e-4f)
    }

    @Test
    fun tallerFrameCropsSides() {
        val m = FrameGeometry.frameToSource(4f / 3f, 1f)
        assertEquals(0.125f, m.mapU(0f, 0.5f), 1e-4f)
        assertEquals(0.875f, m.mapU(1f, 0.5f), 1e-4f)
    }

    @Test
    fun aClockwiseQuarterTurnMovesTheSourceTopToTheFrameRight() {
        val framing = Framing(quarterTurns = 1)
        val aspect = FrameGeometry.outputAspect(1.5f, framing)
        assertEquals(1f / 1.5f, aspect, 1e-4f)
        val m = FrameGeometry.frameToSource(1.5f, aspect, framing)
        // The right-middle of the output frame shows the top-middle of the source.
        assertEquals(0.5f, m.mapU(1f, 0.5f), 1e-3f)
        assertEquals(1f, m.mapV(1f, 0.5f), 1e-3f)
    }

    @Test
    fun straighteningNeverRevealsEmptyCorners() {
        for (angle in listOf(-45f, -12f, -1f, 3.5f, 20f, 45f)) {
            for (aspect in listOf(1f, 1.5f, 16f / 9f, 65f / 24f)) {
                inside(FrameGeometry.frameToSource(1.5f, aspect, Framing(straighten = angle)))
            }
        }
    }

    @Test
    fun zoomAndPanStayInsideTheNegative() {
        for (pan in listOf(-1f, 0f, 1f)) {
            inside(FrameGeometry.frameToSource(4f / 3f, 1.5f, Framing(zoom = 2f, panX = pan, panY = -pan, straighten = 7f)))
        }
    }

    @Test
    fun flippingMirrorsHorizontally() {
        val m = FrameGeometry.frameToSource(1.5f, 1.5f, Framing(flipHorizontal = true))
        assertEquals(1f, m.mapU(0f, 0.5f), 1e-4f)
        assertEquals(0f, m.mapU(1f, 0.5f), 1e-4f)
    }

    @Test
    fun outputSizeIsEvenAndKeepsResolution() {
        assertEquals(4000 to 3000, FrameGeometry.outputSize(4000, 3000, Framing()))
        val (w, h) = FrameGeometry.outputSize(4000, 3000, Framing(aspect = 1.5f))
        assertEquals(4000, w)
        assertEquals(2666, h)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test
    fun formatFollowsPictureOrientation() {
        assertEquals(1.5f, FrameGeometry.outputAspect(4f / 3f, Framing(aspect = 1.5f)), 1e-4f)
        assertEquals(1f / 1.5f, FrameGeometry.outputAspect(3f / 4f, Framing(aspect = 1.5f)), 1e-4f)
    }

    @Test
    fun backCameraBufferIsTurnedUpright() {
        // Sensor mounted at 90°: rotating the buffer clockwise puts its left edge at the top.
        val m = CaptureGeometry.negativeToBuffer(4000, 3000, 90, frontFacing = false, deviceDegrees = 0)
        assertEquals(0f, m.mapU(0.5f, 1f), 1e-3f)
        assertEquals(0.5f, m.mapV(0.5f, 1f), 1e-3f)
        assertEquals(3000 to 4000, CaptureGeometry.negativeSize(4000, 3000, 90, 0))
    }

    @Test
    fun landscapeHoldKeepsSensorAxisHorizontal() {
        // Device turned clockwise: with a 90° sensor the picture needs a half turn from the buffer.
        val m = CaptureGeometry.negativeToBuffer(4000, 3000, 90, frontFacing = false, deviceDegrees = 90)
        assertEquals(4000 to 3000, CaptureGeometry.negativeSize(4000, 3000, 90, 90))
        assertEquals(1f, m.mapU(0f, 0.5f), 1e-3f)
        assertEquals(0.5f, m.mapV(0f, 0.5f), 1e-3f)
    }

    @Test
    fun frontCameraNegativeIsMirroredLikeTheViewfinder() {
        val back = CaptureGeometry.negativeToBuffer(4000, 3000, 270, frontFacing = false, deviceDegrees = 0)
        val front = CaptureGeometry.negativeToBuffer(4000, 3000, 270, frontFacing = true, deviceDegrees = 0)
        assertEquals(back.mapU(0f, 0.25f), front.mapU(1f, 0.25f), 1e-3f)
        assertEquals(back.mapV(0f, 0.25f), front.mapV(1f, 0.25f), 1e-3f)
    }

    @Test
    fun orientationSnapsToQuarterTurns() {
        assertEquals(0, CaptureGeometry.snapDegrees(359))
        assertEquals(90, CaptureGeometry.snapDegrees(70))
        assertEquals(270, CaptureGeometry.snapDegrees(290))
    }
}
