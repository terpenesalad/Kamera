package app.kamera.ui

import android.app.Activity
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import app.kamera.data.LibraryItem
import app.kamera.data.MediaKind
import app.kamera.film.Recipe
import app.kamera.kamera
import java.util.concurrent.Executors

/** The contact sheet: everything shot or imported, newest first. */
class GalleryActivity : Activity() {
    private var items: List<LibraryItem> = emptyList()
    private lateinit var grid: GridView
    private lateinit var empty: View
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].createdMs

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val cell = convertView as? FrameLayout ?: newCell(parent)
            val item = items[position]
            ThumbnailLoader.load(item.thumbnail, cell.getChildAt(0) as ImageView)
            cell.getChildAt(1).visibility = if (item.kind == MediaKind.VIDEO) View.VISIBLE else View.GONE
            return cell
        }
    }

    private fun newCell(parent: ViewGroup): FrameLayout {
        val size = parent.width / COLUMNS
        return FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH, size)
            addView(
                ImageView(this@GalleryActivity).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    setBackgroundColor(Palette.raised)
                },
                frameParams(MATCH, MATCH),
            )
            addView(GlyphButton(this@GalleryActivity, Glyph.PLAY).apply { isClickable = false }, frameParams(dp(30), dp(30), Gravity.BOTTOM or Gravity.END))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = column().apply { setBackgroundColor(Palette.background) }
        val bar = row { setPadding(dp(4), 0, dp(4), 0) }
        bar.addView(GlyphButton(this, Glyph.BACK).apply { setOnClickListener { finish() } }, linearParams(dp(46), dp(52)))
        bar.addView(text("Contact sheet", 18f, Palette.text, Type.condensed), linearParams(0, dp(52), 1f))
        bar.addView(GlyphButton(this, Glyph.IMPORT).apply { setOnClickListener { pickMedia() } }, linearParams(dp(46), dp(52)))
        root.addView(bar)

        val stage = FrameLayout(this)
        grid = GridView(this).apply {
            numColumns = COLUMNS
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            adapter = this@GalleryActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(Intent(this@GalleryActivity, ViewerActivity::class.java).putExtra(ViewerActivity.EXTRA_ID, items[position].id))
            }
        }
        stage.addView(grid, frameParams(MATCH, MATCH))
        empty = text("Nothing developed yet.\nLoad a roll and take a picture.", 15f, Palette.muted, Type.body, Gravity.CENTER)
        stage.addView(empty, frameParams(MATCH, MATCH))
        root.addView(stage, linearParams(MATCH, 0, 1f))

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
        adapter.notifyDataSetChanged()
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    private fun pickMedia() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || uri == null) return
        if (contentResolver.getType(uri)?.startsWith("video/") == true) importVideo(uri) else importPhoto(uri)
    }

    private fun importPhoto(uri: Uri) {
        Toast.makeText(this, "Developing…", Toast.LENGTH_SHORT).show()
        kamera.developer.importPhoto(uri, kamera.settings.stockId) { item ->
            if (item == null) {
                Toast.makeText(this, "That picture could not be opened.", Toast.LENGTH_LONG).show()
            } else {
                openEditor(item)
            }
        }
    }

    private fun importVideo(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val app = kamera
        executor.execute {
            val item = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    fun number(key: Int) = retriever.extractMetadata(key)?.toLongOrNull() ?: 0L
                    val rotation = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val w = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
                    val h = number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
                    val swapped = rotation % 180L != 0L
                    val duration = number(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    app.library.newItem(MediaKind.VIDEO, if (swapped) h else w, if (swapped) w else h, duration, uri, filmBaked = false)
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            if (item != null) {
                item.writeRecipe(Recipe(app.settings.stockId))
                VideoThumbnails.write(this, uri, item.thumbnail)
                app.library.commit(item)
            }
            main.post {
                if (item == null) Toast.makeText(this, "That clip could not be opened.", Toast.LENGTH_LONG).show() else openEditor(item)
            }
        }
    }

    private fun openEditor(item: LibraryItem) {
        startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_ID, item.id))
    }

    private companion object {
        const val COLUMNS = 3
        const val REQUEST_IMPORT = 11
    }
}
