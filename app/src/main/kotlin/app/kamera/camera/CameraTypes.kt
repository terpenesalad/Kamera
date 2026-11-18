package app.kamera.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

enum class CaptureMode { PHOTO, VIDEO }

enum class FlashMode { OFF, AUTO, ON }

/** White-balance choices offered on the camera. */
enum class WhiteBalance(val label: String, val awbMode: Int) {
    AUTO("Auto", CameraMetadata.CONTROL_AWB_MODE_AUTO),
    FILM("Film", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    DAYLIGHT("Sun", CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT),
    CLOUDY("Cloud", CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    SHADE("Shade", CameraMetadata.CONTROL_AWB_MODE_SHADE),
    TUNGSTEN("Lamp", CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT),
    FLUORESCENT("Tube", CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT),
}

/** Everything the photographer controls on the sensor side. Null means automatic. */
data class CameraControls(
    val exposureCompensation: Float = 0f,
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val focusDiopters: Float? = null,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val awbModeOverride: Int? = null,
    val zoom: Float = 1f,
    val flash: FlashMode = FlashMode.OFF,
    val torch: Boolean = false,
)

/** Live readings from the sensor, shown in the exposure readout. */
data class Telemetry(
    val iso: Int,
    val shutterNs: Long,
    val focusDiopters: Float,
    val focused: Boolean,
)

/** A captured frame copied out of the camera's buffers, ready to be developed. */
class StillCapture(
    val width: Int,
    val height: Int,
    val luma: ByteBuffer,
    val chroma: ByteBuffer,
    val sensorDegrees: Int,
    val frontFacing: Boolean,
    val deviceDegrees: Int,
)

/** Capabilities of one camera, resolved once and reused. */
class LensInfo(val id: String, characteristics: CameraCharacteristics) {
    val frontFacing: Boolean =
        characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT
    val sensorDegrees: Int = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    val activeArray: Rect = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
    val hasFlash: Boolean = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    val minFocusDiopters: Float = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    val focalLength: Float = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.5f

    /** 35 mm-equivalent focal length of the main lens at 1×. */
    val equivalentMm: Int = run {
        val size = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        if (size == null) 26 else (focalLength * 43.27f / hypot(size.width, size.height)).roundToInt()
    }

    val evRange: Range<Int> = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStep: Float = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: (1f / 3f)
    val isoRange: Range<Int>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    val shutterRange: Range<Long>? = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    val maxFrameDuration: Long = characteristics.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) ?: 250_000_000L
    val timestampRealtime: Boolean =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

    private val capabilities: IntArray =
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
    val manualSensor: Boolean =
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in capabilities && isoRange != null && shutterRange != null

    val tonemapModes: IntArray = characteristics.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
    val afModes: IntArray = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
    val awbModes: IntArray = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
    val noiseModes: IntArray = characteristics.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
    val edgeModes: IntArray = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
    val stabilizationModes: IntArray =
        characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
    val opticalStabilization: IntArray =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
    val fpsRanges: Array<Range<Int>> =
        characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
    val maxAfRegions: Int = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val maxAeRegions: Int = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

    val zoomRatioRange: Range<Float>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
    val minZoom: Float = zoomRatioRange?.lower ?: 1f
    val maxZoom: Float = zoomRatioRange?.upper
        ?: (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)

    private val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

    /** Sizes the viewfinder stream can use, largest first. */
    val previewSizes: List<Size> = streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        .orEmpty().sortedByDescending { it.width * it.height }

    /** Full-resolution YUV sizes, largest first. */
    val stillSizes: List<Size> = streamMap?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888)
        .orEmpty().sortedByDescending { it.width * it.height }

    /** Highest frame rate the viewfinder stream sustains at [size]. */
    fun maxFps(size: Size): Int {
        val duration = streamMap?.getOutputMinFrameDuration(android.graphics.SurfaceTexture::class.java, size) ?: 0L
        return if (duration <= 0L) 30 else (1_000_000_000f / duration).roundToInt()
    }

    /** Zoom stops worth showing as lens buttons, labelled in 35 mm-equivalent millimetres. */
    fun zoomStops(): List<Float> {
        val stops = ArrayList<Float>()
        if (minZoom < 0.95f) stops.add(minZoom)
        stops.add(1f)
        // The classic 35 mm street-camera view, when the main lens is wider than that.
        val classic = 35f / equivalentMm
        if (classic in 1.1f..maxZoom) stops.add(classic)
        for (z in listOf(2f, 3f, 5f)) if (z <= maxZoom + 0.01f) stops.add(z)
        return stops.take(5)
    }

    /** The largest still size, capped to what the GPU can develop. */
    fun chooseStillSize(maxEdge: Int): Size? {
        val usable = stillSizes.filter { max(it.width, it.height) <= maxEdge && it.width.toLong() * it.height <= MAX_STILL_PIXELS }
        return usable.firstOrNull() ?: stillSizes.lastOrNull()
    }

    /** A viewfinder size matching [aspect], close to [targetLongEdge] without exceeding it. */
    fun choosePreviewSize(aspect: Float, targetLongEdge: Int): Size {
        val matching = previewSizes.filter { kotlin.math.abs(it.width.toFloat() / it.height - aspect) < 0.02f }
        return matching.firstOrNull { max(it.width, it.height) <= targetLongEdge }
            ?: matching.lastOrNull()
            ?: previewSizes.firstOrNull { max(it.width, it.height) <= targetLongEdge }
            ?: previewSizes.last()
    }

    companion object {
        private const val MAX_STILL_PIXELS = 26_000_000L

        /** Picks the first back and front cameras, the ones phones expose as their main modules. */
        fun discover(manager: CameraManager): List<LensInfo> {
            val infos = manager.cameraIdList.mapNotNull { id ->
                runCatching { LensInfo(id, manager.getCameraCharacteristics(id)) }.getOrNull()
            }.filter { it.previewSizes.isNotEmpty() && it.stillSizes.isNotEmpty() }
            return listOfNotNull(infos.firstOrNull { !it.frontFacing }, infos.firstOrNull { it.frontFacing })
        }
    }
}
