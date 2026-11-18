package app.kamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import app.kamera.film.CaptureGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Drives one camera through Camera2.
 *
 * Stills are requested as YUV from the same ISP path as the viewfinder, with exposure and white
 * balance locked to what was on screen at the moment of the shutter press. Vendor still-processing
 * (multi-frame HDR and the like) never runs, because it would make the photo differ from the preview.
 */
class CameraController(context: Context, private val listener: Listener) {
    interface Listener {
        fun onCameraReady(lens: LensInfo, bufferWidth: Int, bufferHeight: Int)
        fun onCameraError(message: String)
        fun onTelemetry(telemetry: Telemetry)
        fun onStillStarted()
        fun onStillCaptured(still: StillCapture)
        fun onStillFailed()
    }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("Camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    val lenses: List<LensInfo> = runCatching { LensInfo.discover(manager) }.getOrDefault(emptyList())

    private var lens: LensInfo? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var stillReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var previewSize = Size(1440, 1080)
    private var mode = CaptureMode.PHOTO
    private var fps = 30
    private var generation = 0
    private var closing = false
    private var pendingOpen: Runnable? = null

    private var controls = CameraControls()
    private var regions: Array<MeteringRectangle>? = null
    private var lastIso = 100
    private var lastShutterNs = 10_000_000L
    private var lastFocus = 0f
    private var afFocused = false
    private var lastTelemetryMs = 0L

    private enum class Phase { IDLE, FOCUSING, PRECAPTURE, LOCKING, EXPOSING }

    private var phase = Phase.IDLE
    private var deviceDegrees = 0
    private var aeLocked = false
    private var awbLocked = false
    private var pendingImage: Image? = null
    private var pendingResult: TotalCaptureResult? = null

    private val phaseTimeout = Runnable { advanceCapture(timedOut = true) }
    private val resumeContinuousFocus = Runnable {
        regions = null
        pushRepeating()
    }

    /** Opens [lensInfo] and starts streaming to [texture]. Safe to call again to switch modes. */
    @SuppressLint("MissingPermission")
    fun start(lensInfo: LensInfo, captureMode: CaptureMode, texture: SurfaceTexture, videoSize: Size?, videoFps: Int, maxStillEdge: Int) {
        handler.post {
            val token = ++generation
            pendingOpen = Runnable { openNow(token, lensInfo, captureMode, texture, videoSize, videoFps, maxStillEdge) }
            closeInternal()
            if (!closing) runPendingOpen()
        }
    }

    private fun runPendingOpen() {
        val open = pendingOpen ?: return
        pendingOpen = null
        open.run()
    }

    @SuppressLint("MissingPermission")
    private fun openNow(
        token: Int,
        lensInfo: LensInfo,
        captureMode: CaptureMode,
        texture: SurfaceTexture,
        videoSize: Size?,
        videoFps: Int,
        maxStillEdge: Int,
    ) {
        if (token != generation) return
        lens = lensInfo
        mode = captureMode
        fps = videoFps
        val still = lensInfo.chooseStillSize(maxStillEdge)
        previewSize = when {
            captureMode == CaptureMode.VIDEO && videoSize != null -> videoSize
            still != null -> lensInfo.choosePreviewSize(still.width.toFloat() / still.height, 1920)
            else -> lensInfo.previewSizes.first()
        }
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(texture)
        if (captureMode == CaptureMode.PHOTO && still != null) {
            stillReader = ImageReader.newInstance(still.width, still.height, ImageFormat.YUV_420_888, 3).also {
                it.setOnImageAvailableListener(::onImageAvailable, handler)
            }
        }
        try {
            manager.openCamera(lensInfo.id, deviceCallback(token), handler)
        } catch (e: CameraAccessException) {
            listener.onCameraError("The camera is unavailable right now.")
        } catch (e: SecurityException) {
            listener.onCameraError("Camera permission is required.")
        }
    }

    private fun deviceCallback(token: Int) = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (token != generation) {
                camera.close()
                return
            }
            device = camera
            createSession(camera, token)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            if (device === camera) device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            if (device === camera) device = null
            listener.onCameraError("The camera stopped unexpectedly (code $error).")
        }

        override fun onClosed(camera: CameraDevice) {
            closing = false
            runPendingOpen()
        }
    }

    private fun createSession(camera: CameraDevice, token: Int) {
        val preview = previewSurface ?: return
        val outputs = ArrayList<OutputConfiguration>()
        outputs.add(OutputConfiguration(preview))
        stillReader?.let { outputs.add(OutputConfiguration(it.surface)) }
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(configured: CameraCaptureSession) {
                if (token != generation) {
                    configured.close()
                    return
                }
                session = configured
                pushRepeating()
                lens?.let { listener.onCameraReady(it, previewSize.width, previewSize.height) }
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                listener.onCameraError("This camera can't run that combination of settings.")
            }
        }
        try {
            camera.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor, callback),
            )
        } catch (e: CameraAccessException) {
            listener.onCameraError("The camera is unavailable right now.")
        } catch (e: IllegalArgumentException) {
            listener.onCameraError("This camera can't run that combination of settings.")
        }
    }

    fun update(newControls: CameraControls) {
        handler.post {
            controls = newControls
            pushRepeating()
        }
    }

    /** Focuses and meters at a point in upright display UV (origin bottom-left). */
    fun focusAt(u: Float, v: Float) {
        handler.post {
            val info = lens ?: return@post
            val active = session ?: return@post
            val cameraDevice = device ?: return@post
            if (phase != Phase.IDLE) return@post
            val map = CaptureGeometry.displayToBuffer(previewSize.width, previewSize.height, info.sensorDegrees, info.frontFacing)
            val rect = meteringRect(info, map.mapU(u, v), map.mapV(u, v))
            regions = arrayOf(MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1))
            handler.removeCallbacks(resumeContinuousFocus)
            try {
                active.stopRepeating()
                val trigger = cameraDevice.createCaptureRequest(templateFor(mode))
                previewSurface?.let(trigger::addTarget)
                applyControls(trigger, still = false)
                trigger.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                active.capture(trigger.build(), repeatingCallback, handler)
                pushRepeating()
            } catch (e: CameraAccessException) {
                Log.w(TAG, "Focus trigger failed", e)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Focus trigger failed", e)
            }
            handler.postDelayed(resumeContinuousFocus, REFOCUS_DELAY_MS)
        }
    }

    private fun meteringRect(info: LensInfo, bufferU: Float, bufferV: Float): Rect {
        val crop = cropRegion(info)
        val bufferAspect = previewSize.width.toFloat() / previewSize.height
        val cropAspect = crop.width().toFloat() / crop.height()
        val visibleW = if (bufferAspect > cropAspect) crop.width().toFloat() else crop.height() * bufferAspect
        val visibleH = if (bufferAspect > cropAspect) crop.width() / bufferAspect else crop.height().toFloat()
        val cx = crop.centerX() + (bufferU - 0.5f) * visibleW
        val cy = crop.centerY() + (bufferV - 0.5f) * visibleH
        val half = (min(visibleW, visibleH) * 0.07f).roundToInt().coerceAtLeast(16)
        val left = (cx.roundToInt() - half).coerceIn(info.activeArray.left, info.activeArray.right - half * 2)
        val top = (cy.roundToInt() - half).coerceIn(info.activeArray.top, info.activeArray.bottom - half * 2)
        return Rect(left, top, left + half * 2, top + half * 2)
    }

    private fun cropRegion(info: LensInfo): Rect {
        val zoom = max(controls.zoom, 1f)
        val w = (info.activeArray.width() / zoom).roundToInt()
        val h = (info.activeArray.height() / zoom).roundToInt()
        val x = info.activeArray.left + (info.activeArray.width() - w) / 2
        val y = info.activeArray.top + (info.activeArray.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    private fun templateFor(captureMode: CaptureMode) =
        if (captureMode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

    private fun pushRepeating() {
        val active = session ?: return
        val camera = device ?: return
        try {
            val builder = camera.createCaptureRequest(templateFor(mode))
            previewSurface?.let(builder::addTarget)
            applyControls(builder, still = false)
            active.setRepeatingRequest(builder.build(), repeatingCallback, handler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Repeating request failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Repeating request failed", e)
        }
    }

    private fun applyControls(b: CaptureRequest.Builder, still: Boolean) {
        val info = lens ?: return
        val c = controls
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val zoomRange = info.zoomRatioRange
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, c.zoom.coerceIn(zoomRange.lower, zoomRange.upper))
        } else {
            b.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(info))
        }

        // Exposure.
        val manual = info.manualSensor && (c.iso != null || c.shutterNs != null)
        if (manual) {
            val iso = (c.iso ?: lastIso).coerceIn(info.isoRange!!.lower, info.isoRange.upper)
            val shutter = (c.shutterNs ?: lastShutterNs).coerceIn(info.shutterRange!!.lower, info.shutterRange.upper)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 1_000_000_000L / max(fps, 1)))
            b.set(CaptureRequest.FLASH_MODE, if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        } else {
            val aeMode = when {
                mode == CaptureMode.VIDEO || !info.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
                c.flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                c.flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            val steps = (c.exposureCompensation / info.evStep).roundToInt().coerceIn(info.evRange.lower, info.evRange.upper)
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps)
            b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
            chooseFpsRange(info)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            b.set(CaptureRequest.FLASH_MODE, if (c.torch) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
        }

        // Focus.
        val focus = c.focusDiopters
        if (focus != null && info.minFocusDiopters > 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focus.coerceIn(0f, info.minFocusDiopters))
        } else if (regions != null && CameraMetadata.CONTROL_AF_MODE_AUTO in info.afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
        } else {
            val continuous = if (mode == CaptureMode.VIDEO) {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            } else {
                CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            }
            if (continuous in info.afModes) b.set(CaptureRequest.CONTROL_AF_MODE, continuous)
        }
        regions?.let {
            if (info.maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, it)
            if (info.maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, it)
        }

        // White balance.
        val awb = c.awbModeOverride ?: c.whiteBalance.awbMode
        b.set(CaptureRequest.CONTROL_AWB_MODE, if (awb in info.awbModes) awb else CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AWB_LOCK, awbLocked)

        // Keep the ISP out of the way: the film look supplies its own texture and acutance.
        val noise = if (still) CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY else CameraMetadata.NOISE_REDUCTION_MODE_FAST
        if (noise in info.noiseModes) b.set(CaptureRequest.NOISE_REDUCTION_MODE, noise)
        if (CameraMetadata.EDGE_MODE_OFF in info.edgeModes) {
            b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        }

        if (mode == CaptureMode.VIDEO &&
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in info.stabilizationModes
        ) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }
        if (CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON in info.opticalStabilization) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun chooseFpsRange(info: LensInfo): Range<Int>? {
        val ranges = info.fpsRanges
        if (ranges.isEmpty()) return null
        return if (mode == CaptureMode.VIDEO) {
            ranges.firstOrNull { it.lower == fps && it.upper == fps }
                ?: ranges.filter { fps in it }.minByOrNull { it.upper - it.lower }
                ?: ranges.minByOrNull { abs(it.upper - fps) }
        } else {
            ranges.filter { it.upper == 30 }.minByOrNull { it.lower }
                ?: ranges.minByOrNull { abs(it.upper - 30) }
        }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastShutterNs = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { lastFocus = it }
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            afFocused = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CameraMetadata.CONTROL_AF_STATE_PASSIVE_FOCUSED
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTelemetryMs > TELEMETRY_INTERVAL_MS) {
                lastTelemetryMs = now
                listener.onTelemetry(Telemetry(lastIso, lastShutterNs, lastFocus, afFocused))
            }
            if (phase != Phase.IDLE && phase != Phase.EXPOSING) checkPhase(result)
        }
    }

    /** Begins the capture sequence for a device held at [orientationDegrees]. */
    fun capture(orientationDegrees: Int) {
        handler.post {
            if (phase != Phase.IDLE) return@post
            if (session == null || stillReader == null) {
                listener.onStillFailed()
                return@post
            }
            deviceDegrees = orientationDegrees
            listener.onStillStarted()
            val info = lens ?: return@post
            val needsFocus = controls.focusDiopters == null && !afFocused && info.minFocusDiopters > 0f &&
                CameraMetadata.CONTROL_AF_MODE_AUTO in info.afModes
            if (needsFocus) {
                enterPhase(Phase.FOCUSING, FOCUS_TIMEOUT_MS)
                sendTrigger { it.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START) }
            } else {
                afterFocus()
            }
        }
    }

    private fun enterPhase(next: Phase, timeoutMs: Long) {
        phase = next
        handler.removeCallbacks(phaseTimeout)
        handler.postDelayed(phaseTimeout, timeoutMs)
    }

    private fun sendTrigger(configure: (CaptureRequest.Builder) -> Unit) {
        val active = session ?: return
        val camera = device ?: return
        try {
            val builder = camera.createCaptureRequest(templateFor(mode))
            previewSurface?.let(builder::addTarget)
            applyControls(builder, still = false)
            configure(builder)
            active.capture(builder.build(), repeatingCallback, handler)
        } catch (e: CameraAccessException) {
            Log.w(TAG, "Trigger failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Trigger failed", e)
        }
    }

    private fun checkPhase(result: CaptureResult) {
        when (phase) {
            Phase.FOCUSING -> {
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                if (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    advanceCapture(timedOut = false)
                }
            }
            Phase.PRECAPTURE -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == CameraMetadata.CONTROL_AE_STATE_CONVERGED ||
                    ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == CameraMetadata.CONTROL_AE_STATE_LOCKED
                ) {
                    advanceCapture(timedOut = false)
                }
            }
            Phase.LOCKING -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                val awb = result.get(CaptureResult.CONTROL_AWB_STATE)
                val aeReady = ae == null || ae == CameraMetadata.CONTROL_AE_STATE_LOCKED || isManual()
                val awbReady = awb == null || awb == CameraMetadata.CONTROL_AWB_STATE_LOCKED
                if (aeReady && awbReady) advanceCapture(timedOut = false)
            }
            else -> Unit
        }
    }

    private fun isManual(): Boolean = (lens?.manualSensor == true) && (controls.iso != null || controls.shutterNs != null)

    private fun advanceCapture(timedOut: Boolean) {
        when (phase) {
            Phase.FOCUSING -> afterFocus()
            Phase.PRECAPTURE -> lockAndExpose()
            Phase.LOCKING -> exposeStill()
            Phase.EXPOSING -> if (timedOut) finishCapture(success = false)
            Phase.IDLE -> Unit
        }
    }

    private fun afterFocus() {
        val info = lens ?: return
        val usesFlash = info.hasFlash && controls.flash != FlashMode.OFF && !isManual()
        if (usesFlash) {
            enterPhase(Phase.PRECAPTURE, PRECAPTURE_TIMEOUT_MS)
            sendTrigger {
                it.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            }
        } else {
            lockAndExpose()
        }
    }

    private fun lockAndExpose() {
        aeLocked = true
        awbLocked = true
        enterPhase(Phase.LOCKING, LOCK_TIMEOUT_MS)
        pushRepeating()
    }

    private fun exposeStill() {
        val active = session ?: return finishCapture(success = false)
        val camera = device ?: return finishCapture(success = false)
        val reader = stillReader ?: return finishCapture(success = false)
        enterPhase(Phase.EXPOSING, EXPOSE_TIMEOUT_MS + lastShutterNs / 1_000_000L)
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            previewSurface?.let(builder::addTarget)
            applyControls(builder, still = true)
            active.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        pendingResult = result
                        deliverIfComplete()
                    }

                    override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        finishCapture(success = false)
                    }
                },
                handler,
            )
        } catch (e: CameraAccessException) {
            finishCapture(success = false)
        } catch (e: IllegalStateException) {
            finishCapture(success = false)
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        if (phase != Phase.EXPOSING) {
            image.close()
            return
        }
        pendingImage?.close()
        pendingImage = image
        deliverIfComplete()
    }

    private fun deliverIfComplete() {
        val image = pendingImage ?: return
        val result = pendingResult ?: return
        val info = lens ?: return
        pendingImage = null
        pendingResult = null
        try {
            val meta = StillMeta(
                iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: lastIso,
                shutterNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: lastShutterNs,
                aperture = result.get(CaptureResult.LENS_APERTURE) ?: info.aperture,
                focalLength = result.get(CaptureResult.LENS_FOCAL_LENGTH) ?: info.focalLength,
            )
            listener.onStillCaptured(copyImage(image, info, meta))
            finishCapture(success = true)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Not enough memory for this photo", e)
            finishCapture(success = false)
        } finally {
            image.close()
        }
    }

    /** Copies an image into tightly packed planes so the camera buffer can be returned at once. */
    private fun copyImage(image: Image, info: LensInfo, meta: StillMeta): StillCapture {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val luma = ByteBuffer.allocateDirect(width * height).order(ByteOrder.nativeOrder())
        val row = ByteArray(width)
        val yBuffer = yPlane.buffer
        for (y in 0 until height) {
            yBuffer.position(y * yPlane.rowStride)
            yBuffer.get(row, 0, width)
            luma.put(row)
        }
        luma.position(0)

        val chromaWidth = width / 2
        val chromaHeight = height / 2
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val chroma = ByteBuffer.allocateDirect(chromaWidth * chromaHeight * 2).order(ByteOrder.nativeOrder())
        val uRowBytes = (chromaWidth - 1) * uPlane.pixelStride + 1
        val vRowBytes = (chromaWidth - 1) * vPlane.pixelStride + 1
        val uRow = ByteArray(uRowBytes)
        val vRow = ByteArray(vRowBytes)
        val packed = ByteArray(chromaWidth * 2)
        for (y in 0 until chromaHeight) {
            uBuffer.position(y * uPlane.rowStride)
            uBuffer.get(uRow, 0, uRowBytes)
            vBuffer.position(y * vPlane.rowStride)
            vBuffer.get(vRow, 0, vRowBytes)
            for (x in 0 until chromaWidth) {
                packed[x * 2] = uRow[x * uPlane.pixelStride]
                packed[x * 2 + 1] = vRow[x * vPlane.pixelStride]
            }
            chroma.put(packed)
        }
        chroma.position(0)
        return StillCapture(width, height, luma, chroma, info.sensorDegrees, info.frontFacing, deviceDegrees, meta)
    }

    private fun finishCapture(success: Boolean) {
        handler.removeCallbacks(phaseTimeout)
        phase = Phase.IDLE
        aeLocked = false
        awbLocked = false
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        if (!success) {
            Log.w(TAG, "Still capture failed")
            listener.onStillFailed()
        }
        sendTrigger { it.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL) }
        pushRepeating()
    }

    fun stop() {
        handler.post {
            generation++
            closeInternal()
        }
    }

    private fun closeInternal() {
        handler.removeCallbacks(phaseTimeout)
        handler.removeCallbacks(resumeContinuousFocus)
        phase = Phase.IDLE
        aeLocked = false
        awbLocked = false
        regions = null
        pendingImage?.close()
        pendingImage = null
        pendingResult = null
        runCatching { session?.close() }
        session = null
        device?.let { camera ->
            closing = true
            runCatching { camera.close() }.onFailure { closing = false }
        }
        device = null
        stillReader?.close()
        stillReader = null
    }

    fun release() {
        handler.post {
            generation++
            closeInternal()
            previewSurface?.release()
            previewSurface = null
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "KameraCamera"
        const val TELEMETRY_INTERVAL_MS = 160L
        const val REFOCUS_DELAY_MS = 6000L
        const val FOCUS_TIMEOUT_MS = 1200L
        const val PRECAPTURE_TIMEOUT_MS = 1800L
        const val LOCK_TIMEOUT_MS = 400L
        const val EXPOSE_TIMEOUT_MS = 3000L
    }
}
