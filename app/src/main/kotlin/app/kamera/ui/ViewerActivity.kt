package app.kamera.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import app.kamera.data.LibraryItem
import app.kamera.data.MediaKind
import app.kamera.film.FilmCatalog
import app.kamera.kamera
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Shows one picture at a time; swipe between frames, pinch to inspect the grain. */
class ViewerActivity : Activity() {
    private var items: List<LibraryItem> = emptyList()
    private var index = 0
    private lateinit var image: ZoomImageView
    private lateinit var video: VideoView
    private lateinit var playButton: GlyphButton
    private lateinit var title: TextView
    private lateinit var detail: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = column().apply { setBackgroundColor(Palette.background) }

        val top = row { setPadding(dp(4), 0, dp(12), 0) }
        top.addView(GlyphButton(this, Glyph.BACK).apply { setOnClickListener { finish() } }, linearParams(dp(46), dp(56)))
        val heading = column(Gravity.CENTER_VERTICAL)
        title = text("", 16f, Palette.text, Type.condensed)
        detail = text("", 11f, Palette.muted, Type.mono)
        heading.addView(title)
        heading.addView(detail, linearParams(WRAP, WRAP).apply { topMargin = dp(3) })
        top.addView(heading, linearParams(0, dp(56), 1f))
        root.addView(top)

        val stage = FrameLayout(this)
        image = ZoomImageView(this) { direction -> show(index + direction) }
        stage.addView(image, frameParams(MATCH, MATCH))
        video = VideoView(this).apply { visibility = View.GONE }
        stage.addView(video, frameParams(MATCH, MATCH, Gravity.CENTER))
        playButton = GlyphButton(this, Glyph.PLAY).apply {
            background = rounded(0x99000000.toInt(), dpf(34))
            visibility = View.GONE
            setOnClickListener { playCurrent() }
        }
        stage.addView(playButton, frameParams(dp(68), dp(68), Gravity.CENTER))
        root.addView(stage, linearParams(MATCH, 0, 1f))

        val bottom = row(Gravity.CENTER)
        bottom.addView(GlyphButton(this, Glyph.SHARE).apply { setOnClickListener { share() } }, linearParams(0, dp(58), 1f))
        bottom.addView(GlyphButton(this, Glyph.EDIT).apply { setOnClickListener { edit() } }, linearParams(0, dp(58), 1f))
        bottom.addView(GlyphButton(this, Glyph.TRASH).apply { setOnClickListener { confirmDelete() } }, linearParams(0, dp(58), 1f))
        root.addView(bottom)

        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        items = kamera.library.all()
        if (items.isEmpty()) {
            finish()
            return
        }
        val id = intent.getStringExtra(EXTRA_ID)
        val found = items.indexOfFirst { it.id == id }
        show(if (found >= 0) found else index.coerceIn(0, items.lastIndex))
    }

    override fun onPause() {
        video.stopPlayback()
        super.onPause()
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun show(position: Int) {
        if (position !in items.indices) return
        index = position
        val item = items[position]
        intent.putExtra(EXTRA_ID, item.id)
        video.stopPlayback()
        video.visibility = View.GONE
        image.visibility = View.VISIBLE
        playButton.visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE

        val stock = item.recipe()?.stockId?.let(FilmCatalog::find)
        title.text = if (item.kind == MediaKind.VIDEO && item.filmBaked) "Motion picture" else stock?.name ?: "Kamera"
        val date = DateFormat.format("d MMM yyyy  HH:mm", item.createdMs)
        detail.text = "$date  ${item.width}×${item.height}"

        val token = ++loadToken
        executor.execute {
            val bitmap = runCatching {
                val uri = item.mediaUri
                if (item.kind == MediaKind.PHOTO && uri != null) {
                    decodeBounded({ contentResolver.openInputStream(uri) }, MAX_VIEW_EDGE)
                } else {
                    null
                }
            }.getOrNull() ?: decodeBounded({ item.thumbnail.takeIf { it.exists() }?.inputStream() }, MAX_VIEW_EDGE)
            main.post { if (token == loadToken) image.setPicture(bitmap) }
        }
    }

    private fun playCurrent() {
        val uri = items.getOrNull(index)?.mediaUri ?: return
        playButton.visibility = View.GONE
        image.visibility = View.GONE
        video.visibility = View.VISIBLE
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
        video.setVideoURI(uri)
        video.start()
    }

    private fun share() {
        val item = items.getOrNull(index) ?: return
        val uri = item.mediaUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = if (item.kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, null))
    }

    private fun edit() {
        val item = items.getOrNull(index) ?: return
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, item.id))
    }

    /** A confirmation drawn by the app itself, so it is as dark as everything else. */
    private fun confirmDelete() {
        val item = items.getOrNull(index) ?: return
        val host = findViewById<ViewGroup>(android.R.id.content) ?: return
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xD9000000.toInt())
            isClickable = true
        }
        val panel = column().apply {
            background = rounded(Palette.surface, dpf(16), Palette.line, dp(1))
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        panel.addView(text("Delete this frame?", 18f, Palette.text, Type.condensed))
        panel.addView(
            text("The picture and its negative will be removed from this device.", 13f, Palette.muted),
            linearParams(MATCH, WRAP).apply {
                topMargin = dp(8)
                bottomMargin = dp(18)
            },
        )
        val actions = row(Gravity.END)
        actions.addView(
            Chip(this, "Keep").apply { setOnClickListener { host.removeView(overlay) } },
            linearParams(WRAP, WRAP).apply { marginEnd = dp(10) },
        )
        actions.addView(
            Chip(this, "Delete").apply {
                setOnClickListener {
                    host.removeView(overlay)
                    item.mediaUri?.takeIf { item.filmBaked || item.kind == MediaKind.PHOTO }?.let(kamera.mediaWriter::delete)
                    kamera.library.remove(item)
                    items = kamera.library.all()
                    if (items.isEmpty()) finish() else show(index.coerceAtMost(items.lastIndex))
                }
            },
        )
        panel.addView(actions, linearParams(MATCH, WRAP))
        overlay.addView(panel, frameParams(dp(300), WRAP, Gravity.CENTER))
        host.addView(overlay, frameParams(MATCH, MATCH))
    }

    companion object {
        const val EXTRA_ID = "id"
        private const val MAX_VIEW_EDGE = 4096
    }
}

/** A picture view with pinch-zoom, panning, double-tap zoom and swipe navigation. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ZoomImageView(context: Context, private val onSwipe: (Int) -> Unit) : View(context) {
    private var bitmap: Bitmap? = null
    private val drawMatrix = Matrix()
    private var scale = 1f
    private var fitScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val scaler = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale > 1.01f) {
                    panX -= dx
                    panY -= dy
                    constrain()
                    invalidate()
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scale <= 1.01f && abs(vx) > abs(vy) && abs(vx) > 600f) onSwipe(if (vx < 0) 1 else -1)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scale > 1.01f) {
                    scale = 1f
                    panX = 0f
                    panY = 0f
                    invalidate()
                } else {
                    zoomBy(3f, e.x, e.y)
                }
                return true
            }
        },
    )

    fun setPicture(picture: Bitmap?) {
        bitmap = picture
        scale = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    private fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        val next = (scale * factor).coerceIn(1f, 12f)
        val applied = next / scale
        panX = (panX - (focusX - width / 2f)) * applied + (focusX - width / 2f)
        panY = (panY - (focusY - height / 2f)) * applied + (focusY - height / 2f)
        scale = next
        constrain()
        invalidate()
    }

    private fun constrain() {
        val picture = bitmap ?: return
        val shownW = picture.width * fitScale * scale
        val shownH = picture.height * fitScale * scale
        val limitX = max((shownW - width) / 2f, 0f)
        val limitY = max((shownH - height) / 2f, 0f)
        panX = panX.coerceIn(-limitX, limitX)
        panY = panY.coerceIn(-limitY, limitY)
    }

    override fun onDraw(canvas: Canvas) {
        val picture = bitmap ?: return
        fitScale = min(width.toFloat() / picture.width, height.toFloat() / picture.height)
        drawMatrix.reset()
        drawMatrix.postTranslate(-picture.width / 2f, -picture.height / 2f)
        drawMatrix.postScale(fitScale * scale, fitScale * scale)
        drawMatrix.postTranslate(width / 2f + panX, height / 2f + panY)
        canvas.drawBitmap(picture, drawMatrix, null)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (!scaler.isInProgress) gestures.onTouchEvent(event)
        return true
    }
}
