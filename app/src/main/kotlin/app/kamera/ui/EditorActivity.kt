package app.kamera.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.kamera.data.LibraryItem
import app.kamera.data.MediaKind
import app.kamera.film.ColorGrade
import app.kamera.film.Effects
import app.kamera.film.FilmCatalog
import app.kamera.film.Framing
import app.kamera.film.HueBands
import app.kamera.film.PhotoFormat
import app.kamera.film.Recipe
import app.kamera.kamera
import app.kamera.render.VideoTranscoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** The darkroom bench: re-develop a negative on any stock and refine it, non-destructively. */
class EditorActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var item: LibraryItem
    private lateinit var recipe: Recipe
    private lateinit var renderer: EditorRenderer
    private lateinit var panel: LinearLayout
    private lateinit var busyOverlay: FrameLayout
    private lateinit var busyLabel: TextView
    private lateinit var playButton: GlyphButton
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var player: MediaPlayer? = null
    private var trimStart = 0f
    private var trimEnd = 1f
    private var mixerBand = 0
    private var transcoder: VideoTranscoder? = null

    private val isVideo get() = item.kind == MediaKind.VIDEO

    private val trimLoop = object : Runnable {
        override fun run() {
            val p = player ?: return
            runCatching {
                if (p.isPlaying && p.currentPosition >= trimEnd * item.durationMs) p.seekTo((trimStart * item.durationMs).toInt())
            }
            main.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val found = kamera.library.find(intent.getStringExtra(EXTRA_ID))
        if (found == null) {
            finish()
            return
        }
        item = found
        recipe = item.recipe() ?: Recipe(if (item.filmBaked) FilmCatalog.NEUTRAL_ID else kamera.settings.stockId)
        window.setDecorFitsSystemWindows(false)
        renderer = EditorRenderer(this)
        renderer.start()
        renderer.setRecipe(recipe)
        buildInterface()
        loadSource()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildInterface() {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.background) }
        val content = column()
        root.addView(content, frameParams(MATCH, MATCH))

        val top = row { setPadding(dp(4), 0, dp(4), 0) }
        top.addView(GlyphButton(this, Glyph.CLOSE).apply { setOnClickListener { finish() } }, linearParams(dp(46), dp(52)))
        top.addView(text("Darkroom", 18f, Palette.text, Type.condensed), linearParams(0, dp(52), 1f))
        val compare = GlyphButton(this, Glyph.COMPARE)
        compare.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> renderer.setShowOriginal(true)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderer.setShowOriginal(false)
            }
            true
        }
        top.addView(compare, linearParams(dp(46), dp(52)))
        top.addView(GlyphButton(this, Glyph.CHECK).apply { tint = Palette.signal; setOnClickListener { save() } }, linearParams(dp(46), dp(52)))
        content.addView(top)

        val stage = FrameLayout(this)
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        stage.addView(surface, frameParams(MATCH, MATCH))
        playButton = GlyphButton(this, Glyph.PLAY).apply {
            background = rounded(0x99000000.toInt(), dpf(26))
            visibility = View.GONE
            setOnClickListener { togglePlayback() }
        }
        stage.addView(playButton, frameParams(dp(52), dp(52), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        content.addView(stage, linearParams(MATCH, 0, 1f))

        val tabs = ChipGroup(this).apply { setPadding(dp(12), dp(10), dp(12), dp(6)); clipToPadding = false }
        val names = ArrayList(listOf("FILM", "FILTERS", "LIGHT", "COLOUR", "EFFECTS", "CROP"))
        if (isVideo) names.add("TRIM")
        tabs.setOptions(names, 0) { showTab(names[it]) }
        content.addView(tabs, linearParams(MATCH, WRAP))

        panel = column().apply { setPadding(dp(8), 0, dp(8), dp(8)) }
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel, frameParams(MATCH, WRAP))
        }
        content.addView(scroller, linearParams(MATCH, dp(248)))

        busyOverlay = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(190, 0, 0, 0))
            isClickable = true
            visibility = View.GONE
            val box = column(Gravity.CENTER)
            box.addView(ProgressBar(this@EditorActivity), linearParams(dp(44), dp(44)))
            busyLabel = text("Developing…", 14f, Palette.text, Type.body, Gravity.CENTER)
            box.addView(busyLabel, linearParams(WRAP, WRAP).apply { topMargin = dp(14) })
            addView(box, frameParams(WRAP, WRAP, Gravity.CENTER))
        }
        root.addView(busyOverlay, frameParams(MATCH, MATCH))

        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            content.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        showTab("FILM")
    }

    private fun loadSource() {
        val uri = item.mediaUri
        if (isVideo && uri != null) {
            playButton.visibility = View.VISIBLE
            prepareVideo(uri)
        } else {
            executor.execute {
                val bitmap = decodeBounded({ item.negative.takeIf { it.exists() }?.inputStream() }, PREVIEW_EDGE)
                main.post {
                    if (bitmap == null) {
                        notice("This picture's negative is missing.", long = true)
                        finish()
                    } else {
                        renderer.setPicture(bitmap)
                    }
                }
            }
        }
    }

    private fun prepareVideo(uri: Uri) {
        renderer.prepareVideo(item.width, item.height) { surface ->
            val mediaPlayer = MediaPlayer()
            try {
                mediaPlayer.setDataSource(this, uri)
                mediaPlayer.setSurface(surface)
                mediaPlayer.isLooping = true
                mediaPlayer.setOnPreparedListener { it.seekTo(0) }
                mediaPlayer.prepareAsync()
                player = mediaPlayer
                main.post(trimLoop)
            } catch (e: Exception) {
                mediaPlayer.release()
                notice("This clip can't be played.", long = true)
            }
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        runCatching {
            if (p.isPlaying) {
                p.pause()
                playButton.glyph = Glyph.PLAY
            } else {
                p.start()
                playButton.glyph = Glyph.PAUSE
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        renderer.attachWindow(holder.surface, width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = renderer.detachWindowBlocking()

    override fun onPause() {
        runCatching { if (player?.isPlaying == true) player?.pause() }
        if (::playButton.isInitialized) playButton.glyph = Glyph.PLAY
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        transcoder?.cancel()
        player?.release()
        player = null
        if (::renderer.isInitialized) renderer.release()
        executor.shutdown()
        super.onDestroy()
    }

    private fun apply(next: Recipe) {
        recipe = next
        renderer.setRecipe(next)
    }

    private fun grade(change: ColorGrade.() -> ColorGrade) = apply(recipe.copy(grade = recipe.grade.change()))

    private fun effects(change: Effects.() -> Effects) = apply(recipe.copy(effects = recipe.effects.change()))

    private fun framing(change: Framing.() -> Framing) = apply(recipe.copy(framing = recipe.framing.change()))

    private fun slider(
        label: String,
        current: Float,
        min: Float = -1f,
        max: Float = 1f,
        neutral: Float = 0f,
        format: (Float) -> String = { ValueSlider.formatSigned(it * 100f) },
        onChange: (Float) -> Unit,
    ) {
        val view = ValueSlider(this, label, min, max, neutral, format, onChange)
        view.setValue(current)
        panel.addView(view, linearParams(MATCH, WRAP))
    }

    private fun heading(title: String) {
        panel.addView(text(title, 10f, Palette.muted, Type.label).apply { setPadding(dp(10), dp(12), 0, dp(6)) })
    }

    private fun showTab(name: String) {
        panel.removeAllViews()
        when (name) {
            "FILM" -> buildFilmTab()
            "FILTERS" -> panel.addView(FilterPanel(this, recipe.filters) { apply(recipe.copy(filters = it)) }, linearParams(MATCH, WRAP))
            "LIGHT" -> buildLightTab()
            "COLOUR" -> buildColourTab()
            "EFFECTS" -> buildEffectsTab()
            "CROP" -> buildCropTab()
            "TRIM" -> buildTrimTab()
        }
    }

    private fun buildFilmTab() {
        if (item.filmBaked) {
            panel.addView(
                text("This clip was recorded on film, so its look is already in the footage. Use the other tabs to grade it.", 13f, Palette.muted)
                    .apply { setPadding(dp(10), dp(12), dp(10), dp(12)) },
            )
            return
        }
        val strip = FilmStrip(this, onPick = { apply(recipe.copy(stockId = it.id)) })
        strip.choose(recipe.stockId)
        panel.addView(strip, linearParams(MATCH, WRAP))
        heading("DEVELOPMENT")
        val pushes = listOf(-1f, 0f, 1f, 2f)
        val process = ChipGroup(this).apply { setPadding(dp(10), 0, dp(10), 0); clipToPadding = false }
        process.setOptions(listOf("Pull −1", "Box speed", "Push +1", "Push +2"), pushes.indexOf(recipe.grade.push).coerceAtLeast(1)) { i ->
            grade { copy(push = pushes[i]) }
        }
        panel.addView(process, linearParams(MATCH, WRAP))
    }

    private fun buildLightTab() {
        slider("Exposure", recipe.effects.exposure, -3f, 3f, format = { "%+.2f".format(it) }) { v -> effects { copy(exposure = v) } }
        slider("Contrast", recipe.grade.contrast) { v -> grade { copy(contrast = v) } }
        slider("Highlights", recipe.grade.highlights) { v -> grade { copy(highlights = v) } }
        slider("Shadows", recipe.grade.shadows) { v -> grade { copy(shadows = v) } }
        slider("Whites", recipe.grade.whites) { v -> grade { copy(whites = v) } }
        slider("Blacks", recipe.grade.blacks) { v -> grade { copy(blacks = v) } }
        slider("Fade", recipe.grade.fade, 0f, 1f) { v -> grade { copy(fade = v) } }
    }

    private fun buildColourTab() {
        slider("Temperature", recipe.effects.temperature) { v -> effects { copy(temperature = v) } }
        slider("Tint", recipe.effects.tint) { v -> effects { copy(tint = v) } }
        slider("Saturation", recipe.grade.saturation) { v -> grade { copy(saturation = v) } }
        slider("Vibrance", recipe.grade.vibrance) { v -> grade { copy(vibrance = v) } }

        heading("COLOUR MIXER")
        val bands = ChipGroup(this).apply { setPadding(dp(10), 0, dp(10), dp(4)); clipToPadding = false }
        val mixer = column()
        fun buildMixer() {
            mixer.removeAllViews()
            val band = mixerBand
            fun mixed(list: List<Float>, v: Float) = list.toMutableList().also { it[band] = v }
            fun add(label: String, value: Float, onChange: (Float) -> Unit) {
                val view = ValueSlider(this, label, -1f, 1f, 0f, onChange = onChange)
                view.setValue(value)
                mixer.addView(view, linearParams(MATCH, WRAP))
            }
            add("Hue", recipe.grade.mixerHue[band]) { v -> grade { copy(mixerHue = mixed(mixerHue, v)) } }
            add("Saturation", recipe.grade.mixerSaturation[band]) { v -> grade { copy(mixerSaturation = mixed(mixerSaturation, v)) } }
            add("Lightness", recipe.grade.mixerLightness[band]) { v -> grade { copy(mixerLightness = mixed(mixerLightness, v)) } }
        }
        bands.setOptions(HueBands.NAMES, mixerBand) { i ->
            mixerBand = i
            buildMixer()
        }
        panel.addView(bands, linearParams(MATCH, WRAP))
        panel.addView(mixer, linearParams(MATCH, WRAP))
        buildMixer()

        heading("SPLIT TONING")
        val hue: (Float) -> String = { "${it.roundToInt()}°" }
        slider("Shadow hue", recipe.grade.shadowToneHue, 0f, 360f, 240f, hue) { v -> grade { copy(shadowToneHue = v) } }
        slider("Shadow amount", recipe.grade.shadowToneAmount, 0f, 1f) { v -> grade { copy(shadowToneAmount = v) } }
        slider("Highlight hue", recipe.grade.highlightToneHue, 0f, 360f, 60f, hue) { v -> grade { copy(highlightToneHue = v) } }
        slider("Highlight amount", recipe.grade.highlightToneAmount, 0f, 1f) { v -> grade { copy(highlightToneAmount = v) } }
    }

    private fun buildEffectsTab() {
        val times: (Float) -> String = { "%.2f×".format(it) }
        slider("Grain amount", recipe.effects.grainAmount, 0f, 3f, 1f, times) { v -> effects { copy(grainAmount = v) } }
        slider("Grain size", recipe.effects.grainSize, 0.5f, 2.5f, 1f, times) { v -> effects { copy(grainSize = v) } }
        slider("Halation", recipe.effects.halation, 0f, 3f, 1f, times) { v -> effects { copy(halation = v) } }
        slider("Bloom", recipe.effects.bloom, 0f, 3f, 1f, times) { v -> effects { copy(bloom = v) } }
        slider("Vignette", recipe.effects.vignette, 0f, 4f, 1f, times) { v -> effects { copy(vignette = v) } }
        slider("Clarity", recipe.effects.clarity) { v -> effects { copy(clarity = v) } }
        slider("Sharpen", recipe.effects.sharpen, 0f, 1f) { v -> effects { copy(sharpen = v) } }
    }

    private fun buildCropTab() {
        heading("FORMAT")
        val formats = listOf<Pair<String, Float>>("Original" to 0f) + PhotoFormat.entries.map { it.label to it.aspect }
        val selected = formats.indexOfFirst { kotlin.math.abs(it.second - recipe.framing.aspect) < 0.01f }.coerceAtLeast(0)
        val chips = ChipGroup(this).apply { setPadding(dp(10), 0, dp(10), 0); clipToPadding = false }
        chips.setOptions(formats.map { it.first }, selected) { i -> framing { copy(aspect = formats[i].second) } }
        panel.addView(chips, linearParams(MATCH, WRAP))

        val tools = row(Gravity.CENTER)
        tools.addView(GlyphButton(this, Glyph.ROTATE).apply { setOnClickListener { framing { copy(quarterTurns = (quarterTurns + 1) % 4) } } }, linearParams(dp(64), dp(48)))
        tools.addView(GlyphButton(this, Glyph.MIRROR).apply { setOnClickListener { framing { copy(flipHorizontal = !flipHorizontal) } } }, linearParams(dp(64), dp(48)))
        panel.addView(tools, linearParams(MATCH, WRAP))

        slider("Straighten", recipe.framing.straighten, -45f, 45f, format = { "%+.1f°".format(it) }) { v -> framing { copy(straighten = v) } }
        slider("Zoom", recipe.framing.zoom, 1f, 4f, 1f, { "%.2f×".format(it) }) { v -> framing { copy(zoom = v) } }
        slider("Pan horizontal", recipe.framing.panX) { v -> framing { copy(panX = v) } }
        slider("Pan vertical", recipe.framing.panY) { v -> framing { copy(panY = v) } }
    }

    private fun buildTrimTab() {
        val time: (Float) -> String = { fraction ->
            val seconds = (fraction * item.durationMs / 1000f).roundToInt()
            "%d:%02d".format(seconds / 60, seconds % 60)
        }
        slider("Start", trimStart, 0f, 1f, 0f, time) { v ->
            trimStart = v.coerceAtMost(trimEnd - 0.01f)
            runCatching { player?.seekTo((trimStart * item.durationMs).toInt()) }
        }
        slider("End", trimEnd, 0f, 1f, 1f, time) { v -> trimEnd = v.coerceAtLeast(trimStart + 0.01f) }
    }

    private fun save() {
        busyOverlay.visibility = View.VISIBLE
        if (isVideo) exportVideo() else developPhoto()
    }

    private fun developPhoto() {
        kamera.developer.redevelop(item, recipe) { updated ->
            busyOverlay.visibility = View.GONE
            if (updated == null) {
                notice("Developing failed. Your original is untouched.", long = true)
            } else {
                notice("Saved")
                finish()
            }
        }
    }

    private fun exportVideo() {
        val source = item.mediaUri ?: return
        runCatching { if (player?.isPlaying == true) player?.pause() }
        val startUs = (trimStart * item.durationMs).toLong() * 1000L
        val endUs = (trimEnd * item.durationMs).toLong() * 1000L
        val job = VideoTranscoder(this, kamera.mediaWriter, source, startUs, endUs, recipe)
        transcoder = job
        val appContext = applicationContext
        executor.execute {
            val result = job.run { progress ->
                main.post { busyLabel.text = "Developing ${(progress * 100).roundToInt()}%" }
            }
            if (result != null) {
                val library = kamera.library
                val created = library.newItem(MediaKind.VIDEO, result.width, result.height, result.durationMs, result.uri, filmBaked = true)
                created.writeRecipe(Recipe(FilmCatalog.NEUTRAL_ID))
                VideoThumbnails.write(appContext, result.uri, created.thumbnail)
                library.commit(created)
            }
            main.post {
                busyOverlay.visibility = View.GONE
                if (result == null) {
                    notice("Export failed. Your original is untouched.", long = true)
                } else {
                    notice("Saved as a new clip")
                    finish()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val PREVIEW_EDGE = 2400
    }
}
