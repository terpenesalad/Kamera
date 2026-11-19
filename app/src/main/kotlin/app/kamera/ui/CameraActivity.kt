package app.kamera.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaActionSound
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.kamera.camera.CameraControls
import app.kamera.camera.CameraController
import app.kamera.camera.CaptureMode
import app.kamera.camera.FlashMode
import app.kamera.camera.LensInfo
import app.kamera.camera.StillCapture
import app.kamera.camera.Telemetry
import app.kamera.camera.WhiteBalance
import app.kamera.data.MediaKind
import app.kamera.data.Settings
import app.kamera.film.CaptureGeometry
import app.kamera.film.ColorGrade
import app.kamera.film.Effects
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmMath
import app.kamera.film.FilmStock
import app.kamera.film.FrameGeometry
import app.kamera.film.Framing
import app.kamera.film.LensFilters
import app.kamera.film.PhotoFormat
import app.kamera.film.Recipe
import app.kamera.film.VideoFormat
import app.kamera.kamera
import app.kamera.render.VideoRecorder
import app.kamera.render.ViewfinderRenderer
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ln
import kotlin.math.roundToInt

/** The camera: a film-loaded viewfinder with full manual control. */
class CameraActivity : Activity(), CameraController.Listener, ViewfinderRenderer.Callbacks, SurfaceHolder.Callback {
    private val settings: Settings get() = kamera.settings
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()

    private lateinit var controller: CameraController
    private lateinit var renderer: ViewfinderRenderer
    private var texture: SurfaceTexture? = null
    private var maxTextureSize = 4096
    private var resumed = false

    private var lens: LensInfo? = null
    private var lensIndex = 0
    private var bufferWidth = 1440
    private var bufferHeight = 1080
    private var mode = CaptureMode.PHOTO
    private var controls = CameraControls()
    private var kelvin: Int? = null
    private var push = 0f
    private var stock: FilmStock = FilmCatalog.default
    private var filters = LensFilters()
    private var telemetry: Telemetry? = null
    private var deviceDegrees = 0

    private var recording = false
    private var recorder: VideoRecorder? = null
    private var recordStart = 0L

    private lateinit var overlay: FinderOverlay
    private lateinit var shutter: ShutterButton
    private lateinit var galleryButton: ImageView
    private lateinit var flipButton: GlyphButton
    private lateinit var flashButton: GlyphButton
    private lateinit var filterButton: GlyphButton
    private lateinit var formatLabel: TextView
    private lateinit var stockLabel: TextView
    private lateinit var rollLabel: TextView
    private lateinit var recordLabel: TextView
    private lateinit var lensRow: LinearLayout
    private lateinit var dial: ScaleDial
    private lateinit var modeChips: ChipGroup
    private lateinit var sheetHost: FrameLayout
    private val readouts = LinkedHashMap<Readout, TextView>()
    private var activeReadout: Readout? = null
    private var sound: MediaActionSound? = null

    private enum class Readout(val title: String) {
        SHUTTER("SHUTTER"), ISO("ISO"), EV("EV"), WB("WB"), FOCUS("FOCUS")
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN || recording) return
                val snapped = CaptureGeometry.snapDegrees(orientation)
                if (snapped != deviceDegrees) {
                    deviceDegrees = snapped
                    renderer.setDeviceDegrees(snapped)
                    rotateControls()
                }
            }
        }
    }

    private val sensorManager by lazy { getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val roll = Math.toDegrees(atan2(event.values[0].toDouble(), event.values[1].toDouble())).toFloat()
            var relative = roll + deviceDegrees
            while (relative > 180f) relative -= 360f
            while (relative < -180f) relative += 360f
            overlay.levelDegrees = overlay.levelDegrees * 0.8f + relative * 0.2f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val recordTicker = object : Runnable {
        override fun run() {
            if (!recording) return
            val seconds = (SystemClock.elapsedRealtime() - recordStart) / 1000
            recordLabel.text = "● %02d:%02d".format(seconds / 60, seconds % 60)
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        stock = FilmCatalog.find(settings.stockId)
        filters = settings.lensFilters
        controller = CameraController(this, this)
        renderer = ViewfinderRenderer(this, this)
        buildInterface()
        renderer.start()
        applyLook()
        applyAssists()
        lens = controller.lenses.firstOrNull()
        if (lens == null) showMessage("No usable camera was found on this device.")
        ensurePermissions()
    }

    private fun ensurePermissions() {
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            showMessage("Kamera needs the camera permission to take pictures.")
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orientationListener.enable()
        sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensorManager.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
        refreshGalleryThumb()
        startCamera()
    }

    override fun onPause() {
        resumed = false
        if (recording) stopRecording()
        orientationListener.disable()
        sensorManager.unregisterListener(gravityListener)
        sound?.release()
        sound = null
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        controller.release()
        renderer.release()
        background.shutdown()
        super.onDestroy()
    }

    // region Interface

    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.background) }
        val content = column()
        root.addView(content, frameParams(MATCH, MATCH))

        content.addView(buildTopBar(), linearParams(MATCH, dp(52)))

        val finder = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        finder.addView(surface, frameParams(MATCH, MATCH))
        overlay = FinderOverlay(this)
        finder.addView(overlay, frameParams(MATCH, MATCH))
        recordLabel = text("", 14f, Palette.record, Type.mono, Gravity.CENTER).apply { visibility = View.GONE }
        finder.addView(recordLabel, frameParams(WRAP, dp(32), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        attachFinderGestures(overlay)
        content.addView(finder, linearParams(MATCH, 0, 1f))

        content.addView(buildReadoutBar(), linearParams(MATCH, dp(46)))
        dial = ScaleDial(this).apply { visibility = View.GONE }
        content.addView(dial, linearParams(MATCH, dp(46)))
        lensRow = row(Gravity.CENTER)
        content.addView(lensRow, linearParams(MATCH, dp(42)))

        modeChips = ChipGroup(this)
        modeChips.setOptions(listOf("PHOTO", "VIDEO"), 0) { switchMode(if (it == 0) CaptureMode.PHOTO else CaptureMode.VIDEO) }
        content.addView(modeChips, linearParams(WRAP, dp(42), gravity = Gravity.CENTER_HORIZONTAL))

        content.addView(buildShutterBar(), linearParams(MATCH, dp(104)))

        sheetHost = FrameLayout(this).apply { visibility = View.GONE }
        root.addView(sheetHost, frameParams(MATCH, MATCH))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            sheetHost.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        refreshFilmLabels()
        refreshFormatLabel()
        refreshFlash()
    }

    private fun buildTopBar() = row {
        setPadding(dp(6), 0, dp(6), 0)
        flashButton = GlyphButton(this@CameraActivity, Glyph.FLASH_OFF)
        flashButton.setOnClickListener { cycleFlash() }
        flashButton.contentDescription = "Flash"
        addView(flashButton, linearParams(dp(44), dp(44)))
        formatLabel = text("3:2", 13f, Palette.text, Type.mono, Gravity.CENTER)
        formatLabel.setOnClickListener { cycleFormat() }
        addView(formatLabel, linearParams(dp(58), dp(44)))

        val film = column(Gravity.CENTER)
        stockLabel = text("", 15f, Palette.text, Type.condensed, Gravity.CENTER)
        rollLabel = text("", 10f, Palette.muted, Type.mono, Gravity.CENTER).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        film.addView(stockLabel)
        film.addView(rollLabel)
        film.isClickable = true
        film.pressDims()
        film.setOnClickListener { showFilmCabinet() }
        addView(film, linearParams(0, MATCH, 1f))

        filterButton = GlyphButton(this@CameraActivity, Glyph.FILTER)
        filterButton.setOnClickListener { showFilterHolder() }
        filterButton.contentDescription = "Filters"
        addView(filterButton, linearParams(dp(58), dp(44)))
        val settingsButton = GlyphButton(this@CameraActivity, Glyph.SETTINGS)
        settingsButton.setOnClickListener { showSettings() }
        settingsButton.contentDescription = "Settings"
        addView(settingsButton, linearParams(dp(44), dp(44)))
    }

    private fun buildReadoutBar() = row {
        setPadding(dp(4), 0, dp(4), 0)
        for (item in Readout.entries) {
            val cell = column(Gravity.CENTER)
            cell.addView(text(item.title, 9f, Palette.faint, Type.label, Gravity.CENTER))
            val value = text("—", 13f, Palette.text, Type.mono, Gravity.CENTER)
            cell.addView(value, linearParams(WRAP, WRAP).apply { topMargin = dp(3) })
            readouts[item] = value
            cell.isClickable = true
            cell.setOnClickListener { toggleReadout(item) }
            addView(cell, linearParams(0, MATCH, 1f))
        }
    }

    private fun buildShutterBar() = FrameLayout(this).apply {
        galleryButton = ImageView(this@CameraActivity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Palette.raised, dpf(10))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(10))
                }
            }
            clipToOutline = true
            pressDims()
            setOnClickListener { startActivity(Intent(this@CameraActivity, GalleryActivity::class.java)) }
            contentDescription = "Gallery"
        }
        addView(galleryButton, frameParams(dp(52), dp(52), Gravity.CENTER_VERTICAL or Gravity.START).apply { marginStart = dp(26) })
        shutter = ShutterButton(this@CameraActivity)
        shutter.setOnClickListener { pressShutter() }
        shutter.contentDescription = "Shutter"
        addView(shutter, frameParams(dp(78), dp(78), Gravity.CENTER))
        flipButton = GlyphButton(this@CameraActivity, Glyph.FLIP)
        flipButton.setOnClickListener { flipCamera() }
        flipButton.contentDescription = "Switch camera"
        addView(flipButton, frameParams(dp(52), dp(52), Gravity.CENTER_VERTICAL or Gravity.END).apply { marginEnd = dp(26) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachFinderGestures(view: View) {
        val scale = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val info = lens ?: return false
                    val zoom = (controls.zoom * detector.scaleFactor).coerceIn(info.minZoom, info.maxZoom)
                    setControls(controls.copy(zoom = zoom))
                    refreshLensRow()
                    return true
                }
            },
        )
        val taps = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAtScreen(e.x, e.y)
                    return true
                }
            },
        )
        view.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) taps.onTouchEvent(event)
            true
        }
    }

    private fun focusAtScreen(x: Float, y: Float) {
        val info = lens ?: return
        val rect = overlay.frameRect()
        if (rect.isEmpty || !rect.contains(x, y)) return
        overlay.showFocusAt(x, y)
        val natural = if (info.sensorDegrees % 180 != 0) bufferHeight.toFloat() / bufferWidth else bufferWidth.toFloat() / bufferHeight
        val aspect = rect.width() / rect.height()
        val map = FrameGeometry.frameToSource(natural, aspect, Framing(quarterTurns = displayDegrees() / 90))
        val u = (x - rect.left) / rect.width()
        val v = 1f - (y - rect.top) / rect.height()
        controller.focusAt(map.mapU(u, v), map.mapV(u, v))
    }

    private fun displayDegrees(): Int = (display?.rotation ?: 0) * 90

    private fun rotateControls() {
        val target = -(if (deviceDegrees > 180) deviceDegrees - 360 else deviceDegrees).toFloat()
        for (view in listOf(galleryButton, flipButton, flashButton, formatLabel, filterButton)) {
            view.animate().rotation(target).setDuration(220).start()
        }
    }

    private fun showMessage(message: String) = notice(message, long = true)

    // endregion

    // region Camera lifecycle

    override fun onTextureReady(texture: SurfaceTexture, maxTextureSize: Int) {
        this.texture = texture
        this.maxTextureSize = maxTextureSize
        startCamera()
    }

    private fun startCamera() {
        val surfaceTexture = texture ?: return
        val info = lens ?: return
        if (!resumed || checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        val videoSize = if (mode == CaptureMode.VIDEO) chooseVideoStream(info) else null
        controller.start(info, mode, surfaceTexture, videoSize, settings.videoFps, maxTextureSize)
        setControls(controls.copy(zoom = controls.zoom.coerceIn(info.minZoom, info.maxZoom)))
        refreshLensRow()
    }

    private fun chooseVideoStream(info: LensInfo): Size =
        info.choosePreviewSize(16f / 9f, settings.videoHeight * 16 / 9)

    override fun onCameraReady(lens: LensInfo, bufferWidth: Int, bufferHeight: Int) {
        renderer.setCameraGeometry(bufferWidth, bufferHeight, lens.sensorDegrees, displayDegrees(), lens.timestampRealtime)
        main.post {
            this.bufferWidth = bufferWidth
            this.bufferHeight = bufferHeight
        }
    }

    override fun onCameraError(message: String) {
        main.post { showMessage(message) }
    }

    override fun onTelemetry(telemetry: Telemetry) {
        main.post {
            this.telemetry = telemetry
            refreshReadouts()
        }
    }

    override fun onHistogram(bins: FloatArray) {
        overlay.histogram = if (settings.showHistogram) bins else null
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.attachWindow(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detachWindowBlocking()

    private fun setControls(next: CameraControls) {
        controls = next
        controller.update(next)
        refreshReadouts()
    }

    /** The recipe the camera is loaded with; the viewfinder and every capture share it. */
    private fun currentRecipe(): Recipe {
        val temperature = kelvin?.let { k ->
            val gains = FilmMath.kelvinGains(k.toFloat())
            (ln((gains[0] / gains[2]).toDouble()) / ln(2.0) / (2 * 0.42)).toFloat().coerceIn(-1f, 1f)
        } ?: 0f
        return Recipe(
            stockId = stock.id,
            grade = ColorGrade(push = push),
            effects = Effects(temperature = temperature),
            framing = Framing(aspect = settings.photoFormat.aspect),
            filters = filters,
        )
    }

    private fun applyLook() {
        renderer.setLook(stock, currentRecipe())
        val aspect = if (mode == CaptureMode.VIDEO) settings.videoFormat.aspect else settings.photoFormat.aspect
        renderer.setFrameAspect(aspect)
        overlay.longAspect = aspect
    }

    private fun applyAssists() {
        overlay.showGrid = settings.showGrid
        overlay.showLevel = settings.showLevel
        if (!settings.showHistogram) overlay.histogram = null
        renderer.setAssists(settings.zebra, settings.peaking && controls.focusDiopters != null, settings.showHistogram)
    }

    // endregion

    // region Capture

    private fun pressShutter() {
        when {
            mode == CaptureMode.VIDEO -> if (recording) stopRecording() else startRecording()
            kamera.developer.backlog >= MAX_DARKROOM_BACKLOG -> {
                notice("Developing… one moment")
            }
            else -> controller.capture(deviceDegrees)
        }
    }

    override fun onStillStarted() {
        main.post {
            overlay.blink()
            shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (settings.shutterSound || shutterSoundRequired()) sound?.play(MediaActionSound.SHUTTER_CLICK)
        }
    }

    override fun onStillCaptured(still: StillCapture) {
        val recipe = currentRecipe()
        main.post {
            settings.advanceFrame(stock.id)
            refreshFilmLabels()
            shutter.busy = true
        }
        kamera.developer.developCapture(still, recipe) { item ->
            shutter.busy = kamera.developer.backlog > 0
            if (item == null) showMessage("That photo could not be developed.") else refreshGalleryThumb()
        }
    }

    override fun onStillFailed() {
        main.post { showMessage("The camera missed that shot. Try again.") }
    }

    /** Some regions legally require an audible shutter; the system reports this from Android 13. */
    private fun shutterSoundRequired(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && MediaActionSound.mustPlayShutterSound()

    private fun startRecording() {
        val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val (width, height) = recordSize()
        val active = try {
            VideoRecorder(kamera.mediaWriter, width, height, settings.videoFps, audio).also { it.start() }
        } catch (e: Exception) {
            showMessage("Recording isn't available at these settings.")
            return
        }
        recorder = active
        renderer.startRecording(active, deviceDegrees)
        recording = true
        recordStart = SystemClock.elapsedRealtime()
        shutter.recording = true
        recordLabel.visibility = View.VISIBLE
        modeChips.visibility = View.INVISIBLE
        flipButton.isEnabled = false
        recordTicker.run()
    }

    private fun recordSize(): Pair<Int, Int> {
        val long = maxOf(bufferWidth, bufferHeight)
        val short = ((long / settings.videoFormat.aspect).roundToInt()) and 1.inv()
        return if (deviceDegrees % 180 == 0) short to long else long to short
    }

    private fun stopRecording() {
        recording = false
        recorder = null
        shutter.recording = false
        recordLabel.visibility = View.GONE
        modeChips.visibility = View.VISIBLE
        flipButton.isEnabled = true
        renderer.stopRecording { result ->
            if (result == null) {
                showMessage("That clip was too short to keep.")
            } else {
                val appContext = applicationContext
                background.execute {
                    val library = kamera.library
                    val item = library.newItem(MediaKind.VIDEO, result.width, result.height, result.durationMs, result.uri, filmBaked = true)
                    item.writeRecipe(Recipe(FilmCatalog.NEUTRAL_ID))
                    VideoThumbnails.write(appContext, result.uri, item.thumbnail)
                    library.commit(item)
                    main.post { refreshGalleryThumb() }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_CAMERA) {
            if (event.repeatCount == 0) pressShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // endregion

    // region Controls

    private fun switchMode(next: CaptureMode) {
        if (next == mode || recording) return
        mode = next
        shutter.videoMode = next == CaptureMode.VIDEO
        controls = controls.copy(torch = false)
        refreshFlash()
        refreshFormatLabel()
        applyLook()
        startCamera()
    }

    private fun flipCamera() {
        if (recording || controller.lenses.size < 2) return
        lensIndex = (lensIndex + 1) % controller.lenses.size
        lens = controller.lenses[lensIndex]
        controls = controls.copy(zoom = 1f, focusDiopters = null, iso = null, shutterNs = null, torch = false)
        activeReadout = null
        dial.visibility = View.GONE
        startCamera()
    }

    private fun cycleFlash() {
        val info = lens ?: return
        if (!info.hasFlash) return
        if (mode == CaptureMode.VIDEO) {
            setControls(controls.copy(torch = !controls.torch))
        } else {
            val next = FlashMode.entries[(controls.flash.ordinal + 1) % FlashMode.entries.size]
            setControls(controls.copy(flash = next))
        }
        refreshFlash()
    }

    private fun refreshFlash() {
        val lit = if (mode == CaptureMode.VIDEO) controls.torch else controls.flash != FlashMode.OFF
        flashButton.glyph = if (lit) Glyph.FLASH else Glyph.FLASH_OFF
        flashButton.active = lit
    }

    private fun cycleFormat() {
        if (recording) return
        if (mode == CaptureMode.VIDEO) {
            val all = VideoFormat.entries
            settings.videoFormat = all[(settings.videoFormat.ordinal + 1) % all.size]
        } else {
            val all = PhotoFormat.entries
            settings.photoFormat = all[(settings.photoFormat.ordinal + 1) % all.size]
        }
        refreshFormatLabel()
        applyLook()
    }

    private fun refreshFormatLabel() {
        formatLabel.text = if (mode == CaptureMode.VIDEO) settings.videoFormat.label else settings.photoFormat.label
    }

    private fun refreshFilmLabels() {
        stockLabel.text = stock.name.uppercase()
        val frame = settings.framesExposed(stock.id)
        val pushText = when {
            push > 0f -> "  PUSH +${push.toInt()}"
            push < 0f -> "  PULL ${push.toInt()}"
            else -> ""
        }
        val filterText = if (filters.isEmpty) "" else "  ·  " + filters.names.joinToString(" + ")
        rollLabel.text = "$frame/${Settings.ROLL_LENGTH}$pushText$filterText"
        filterButton.active = !filters.isEmpty
    }

    private fun refreshLensRow() {
        val info = lens ?: return
        lensRow.removeAllViews()
        for (stop in info.zoomStops()) {
            val selected = abs(controls.zoom - stop) < 0.05f
            val label = text("${(info.equivalentMm * stop).roundToInt()}", 12f, if (selected) Palette.amber else Palette.muted, Type.mono, Gravity.CENTER)
            label.setOnClickListener {
                setControls(controls.copy(zoom = stop))
                refreshLensRow()
            }
            lensRow.addView(label, linearParams(dp(52), MATCH))
        }
    }

    private fun refreshGalleryThumb() {
        background.execute {
            val thumb = kamera.library.latest()?.thumbnail?.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
            main.post { galleryButton.setImageBitmap(thumb) }
        }
    }

    private fun refreshReadouts() {
        val t = telemetry
        val manualShutter = controls.shutterNs
        readouts[Readout.SHUTTER]?.apply {
            text = (manualShutter ?: t?.shutterNs)?.let(::shutterLabel) ?: "—"
            setTextColor(if (manualShutter != null) Palette.amber else Palette.text)
        }
        readouts[Readout.ISO]?.apply {
            text = (controls.iso ?: t?.iso)?.toString() ?: "—"
            setTextColor(if (controls.iso != null) Palette.amber else Palette.text)
        }
        readouts[Readout.EV]?.apply {
            text = "%+.1f".format(controls.exposureCompensation)
            setTextColor(if (controls.exposureCompensation != 0f) Palette.amber else Palette.text)
        }
        readouts[Readout.WB]?.apply {
            text = kelvin?.let { "${it}K" } ?: controls.whiteBalance.label
            setTextColor(if (kelvin != null || controls.whiteBalance != WhiteBalance.AUTO) Palette.amber else Palette.text)
        }
        readouts[Readout.FOCUS]?.apply {
            text = controls.focusDiopters?.let(::focusLabel) ?: "AF"
            setTextColor(if (controls.focusDiopters != null) Palette.amber else if (t?.focused == true) Palette.text else Palette.muted)
        }
    }

    private fun toggleReadout(item: Readout) {
        if (activeReadout == item) {
            activeReadout = null
            dial.visibility = View.GONE
            return
        }
        val info = lens ?: return
        activeReadout = item
        dial.visibility = View.VISIBLE
        when (item) {
            Readout.SHUTTER -> configureShutterDial(info)
            Readout.ISO -> configureIsoDial(info)
            Readout.EV -> configureEvDial(info)
            Readout.WB -> configureWbDial()
            Readout.FOCUS -> configureFocusDial(info)
        }
    }

    private fun configureShutterDial(info: LensInfo) {
        val range = info.shutterRange
        if (!info.manualSensor || range == null) return dialUnavailable("Shutter is automatic on this camera")
        val values = SHUTTER_STOPS.filter { it in range.lower..range.upper }
        val labels = listOf("AUTO") + values.map(::shutterLabel)
        dial.configure(labels, values.indexOf(controls.shutterNs) + 1) { i ->
            setControls(controls.copy(shutterNs = values.getOrNull(i - 1)))
        }
    }

    private fun configureIsoDial(info: LensInfo) {
        val range = info.isoRange
        if (!info.manualSensor || range == null) return dialUnavailable("ISO is automatic on this camera")
        val values = ISO_STOPS.filter { it in range.lower..range.upper }
        dial.configure(listOf("AUTO") + values.map { it.toString() }, values.indexOf(controls.iso) + 1) { i ->
            setControls(controls.copy(iso = values.getOrNull(i - 1)))
        }
    }

    private fun configureEvDial(info: LensInfo) {
        val low = info.evRange.lower * info.evStep
        val high = info.evRange.upper * info.evStep
        val values = (-9..9).map { it / 3f }.filter { it >= low - 0.01f && it <= high + 0.01f }
        val selected = values.indices.minByOrNull { abs(values[it] - controls.exposureCompensation) } ?: 0
        dial.configure(values.map { "%+.1f".format(it) }, selected) { i ->
            setControls(controls.copy(exposureCompensation = values[i]))
        }
    }

    private fun configureWbDial() {
        val presets = WhiteBalance.entries
        val labels = presets.map { it.label } + KELVIN_STOPS.map { "${it}K" }
        val selected = kelvin?.let { presets.size + KELVIN_STOPS.indexOf(it) } ?: controls.whiteBalance.ordinal
        dial.configure(labels, selected) { i ->
            if (i < presets.size) {
                kelvin = null
                val choice = presets[i]
                val override = if (choice == WhiteBalance.FILM && stock.balanceKelvin < 4000) WhiteBalance.TUNGSTEN.awbMode else null
                setControls(controls.copy(whiteBalance = choice, awbModeOverride = override))
            } else {
                kelvin = KELVIN_STOPS[i - presets.size]
                setControls(controls.copy(whiteBalance = WhiteBalance.DAYLIGHT, awbModeOverride = null))
            }
            applyLook()
            refreshReadouts()
        }
    }

    private fun configureFocusDial(info: LensInfo) {
        if (info.minFocusDiopters <= 0f) return dialUnavailable("This lens has fixed focus")
        val steps = 30
        val values = (0..steps).map { i -> val t = i / steps.toFloat(); info.minFocusDiopters * t * t }
        val selected = controls.focusDiopters?.let { f -> values.indices.minByOrNull { abs(values[it] - f) }?.plus(1) } ?: 0
        dial.configure(listOf("AF") + values.map(::focusLabel), selected) { i ->
            setControls(controls.copy(focusDiopters = values.getOrNull(i - 1)))
            applyAssists()
        }
    }

    private fun dialUnavailable(message: String) {
        activeReadout = null
        dial.visibility = View.GONE
        notice(message)
    }

    // endregion

    // region Sheets

    private fun openSheet(title: String, body: View) {
        sheetHost.removeAllViews()
        val scrim = View(this).apply {
            setBackgroundColor(0x99000000.toInt())
            setOnClickListener { closeSheet() }
        }
        sheetHost.addView(scrim, frameParams(MATCH, MATCH))
        val panel = column().apply {
            background = rounded(Palette.surface, dpf(18))
            setPadding(0, dp(14), 0, dp(14))
            isClickable = true
        }
        val header = row { setPadding(dp(16), 0, dp(6), 0) }
        header.addView(text(title, 16f, Palette.text, Type.condensed), linearParams(0, dp(36), 1f))
        val close = GlyphButton(this, Glyph.CLOSE)
        close.setOnClickListener { closeSheet() }
        header.addView(close, linearParams(dp(40), dp(36)))
        panel.addView(header)
        panel.addView(body)
        sheetHost.addView(panel, frameParams(MATCH, WRAP, Gravity.BOTTOM))
        sheetHost.visibility = View.VISIBLE
    }

    private fun closeSheet() {
        sheetHost.visibility = View.GONE
        sheetHost.removeAllViews()
    }

    private fun showFilmCabinet() {
        val body = column()
        val detail = text(stockDetail(stock), 13f, Palette.muted).apply { setPadding(dp(16), dp(4), dp(16), dp(12)); minLines = 2 }
        body.addView(detail)
        val strip = FilmStrip(this, onPick = { picked ->
            stock = picked
            settings.stockId = picked.id
            detail.text = stockDetail(picked)
            refreshFilmLabels()
            applyLook()
        })
        strip.choose(stock.id)
        body.addView(strip, linearParams(MATCH, WRAP))
        body.addView(text("DEVELOPMENT", 10f, Palette.muted, Type.label).apply { setPadding(dp(16), dp(14), 0, dp(8)) })
        val process = ChipGroup(this).apply { setPadding(dp(16), 0, dp(16), 0); clipToPadding = false }
        val pushes = listOf(-1f, 0f, 1f, 2f)
        process.setOptions(listOf("Pull −1", "Box speed", "Push +1", "Push +2"), pushes.indexOf(push)) { i ->
            push = pushes[i]
            refreshFilmLabels()
            applyLook()
        }
        body.addView(process, linearParams(MATCH, WRAP))
        openSheet("Film cabinet", body)
    }

    private fun showFilterHolder() {
        val panel = FilterPanel(this, filters) { next ->
            filters = next
            settings.lensFilters = next
            refreshFilmLabels()
            applyLook()
        }
        val scroller = ScrollView(this).apply { addView(panel) }
        openSheet("Filter holder", scroller.apply { layoutParams = linearParams(MATCH, dp(430)) })
    }

    private fun stockDetail(s: FilmStock) = "${s.character}\n${s.reference}"

    private fun showSettings() {
        val body = column().apply { setPadding(dp(16), 0, dp(16), 0) }
        fun section(title: String) = body.addView(
            text(title, 10f, Palette.muted, Type.label).apply { setPadding(0, dp(12), 0, dp(8)) },
        )
        fun options(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
            body.addView(ChipGroup(this).apply { setOptions(labels, selected, onSelect) }, linearParams(MATCH, WRAP))
        }

        section("PHOTO FORMAT")
        val photo = PhotoFormat.entries
        options(photo.map { "${it.label} ${it.detail}" }, settings.photoFormat.ordinal) { i ->
            settings.photoFormat = photo[i]
            refreshFormatLabel()
            applyLook()
        }
        section("VIDEO FORMAT")
        val video = VideoFormat.entries
        options(video.map { it.label }, settings.videoFormat.ordinal) { i ->
            settings.videoFormat = video[i]
            refreshFormatLabel()
            applyLook()
        }
        section("VIDEO RESOLUTION")
        val heights = listOf(1080, 2160)
        options(listOf("1080p", "4K"), heights.indexOf(settings.videoHeight).coerceAtLeast(0)) { i ->
            settings.videoHeight = heights[i]
            if (mode == CaptureMode.VIDEO) startCamera()
        }
        section("VIDEO FRAME RATE")
        val rates = listOf(24, 25, 30, 60)
        options(rates.map { "$it fps" }, rates.indexOf(settings.videoFps).coerceAtLeast(0)) { i ->
            settings.videoFps = rates[i]
            if (mode == CaptureMode.VIDEO) startCamera()
        }
        section("ASSISTS")
        body.addView(ToggleRow(this, "Composition grid", settings.showGrid) { settings.showGrid = it; applyAssists() })
        body.addView(ToggleRow(this, "Spirit level", settings.showLevel) { settings.showLevel = it; applyAssists() })
        body.addView(ToggleRow(this, "Histogram", settings.showHistogram) { settings.showHistogram = it; applyAssists() })
        body.addView(ToggleRow(this, "Highlight zebras", settings.zebra) { settings.zebra = it; applyAssists() })
        body.addView(ToggleRow(this, "Focus peaking (manual focus)", settings.peaking) { settings.peaking = it; applyAssists() })
        body.addView(ToggleRow(this, "Shutter sound", settings.shutterSound) { settings.shutterSound = it })
        val scroller = ScrollView(this).apply { addView(body) }
        openSheet("Settings", scroller.apply { layoutParams = linearParams(MATCH, dp(440)) })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (sheetHost.visibility == View.VISIBLE) closeSheet() else super.onBackPressed()
    }

    // endregion

    private companion object {
        const val REQUEST_PERMISSIONS = 7
        const val MAX_DARKROOM_BACKLOG = 3

        val ISO_STOPS = listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        val KELVIN_STOPS = listOf(2500, 2800, 3200, 3600, 4000, 4500, 5000, 5500, 6000, 6500, 7000, 8000, 9000, 10000)
        val SHUTTER_STOPS: List<Long> = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2)
            .map { 1_000_000_000L / it } + listOf(1_000_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 15_000_000_000L, 30_000_000_000L)

        fun shutterLabel(ns: Long): String =
            if (ns >= 500_000_000L) "${(ns / 1e9).roundToInt()}s" else "1/${(1e9 / ns).roundToInt()}"

        fun focusLabel(diopters: Float): String = when {
            diopters <= 0.02f -> "∞"
            1f / diopters >= 1f -> "%.1fm".format(1f / diopters)
            else -> "${(100f / diopters).roundToInt()}cm"
        }
    }
}
