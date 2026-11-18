package app.kamera.data

import android.content.Context
import android.net.Uri
import app.kamera.film.Recipe
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

enum class MediaKind { PHOTO, VIDEO }

/**
 * One picture or clip. Photos keep their undeveloped negative and recipe so they can be developed
 * again on any stock at any time; the delivered file lives in the shared photo collection.
 */
class LibraryItem(
    val id: String,
    val kind: MediaKind,
    val dir: File,
    val createdMs: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long,
    val mediaUri: Uri?,
    /** True when the file already carries a film look baked in during recording. */
    val filmBaked: Boolean,
) {
    val negative: File get() = File(dir, "negative.jpg")
    val thumbnail: File get() = File(dir, "thumb.jpg")
    private val recipeFile: File get() = File(dir, "recipe.txt")

    fun recipe(): Recipe? = runCatching { Recipe.decode(recipeFile.readText()) }.getOrNull()

    fun writeRecipe(recipe: Recipe) = recipeFile.writeText(recipe.encode())

    fun copy(mediaUri: Uri? = this.mediaUri, width: Int = this.width, height: Int = this.height, durationMs: Long = this.durationMs) =
        LibraryItem(id, kind, dir, createdMs, width, height, durationMs, mediaUri, filmBaked)
}

/** The on-device index of everything shot or imported, newest first. */
class Library(context: Context) {
    private val root = File(context.filesDir, "library").apply { mkdirs() }
    private val items = CopyOnWriteArrayList<LibraryItem>()

    init {
        val loaded = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull(::read)
        items.addAll(loaded.sortedByDescending { it.createdMs })
    }

    fun all(): List<LibraryItem> = items.toList()

    fun find(id: String?): LibraryItem? = items.firstOrNull { it.id == id }

    fun latest(): LibraryItem? = items.firstOrNull()

    fun newItem(kind: MediaKind, width: Int, height: Int, durationMs: Long, mediaUri: Uri?, filmBaked: Boolean): LibraryItem {
        val now = System.currentTimeMillis()
        val id = "$now-${(1000..9999).random()}"
        val dir = File(root, id).apply { mkdirs() }
        return LibraryItem(id, kind, dir, now, width, height, durationMs, mediaUri, filmBaked)
    }

    /** Writes the item's metadata and adds it to, or refreshes it in, the index. */
    fun commit(item: LibraryItem) {
        File(item.dir, "meta.txt").writeText(
            buildString {
                append("kind=").append(item.kind.name).append('\n')
                append("created=").append(item.createdMs).append('\n')
                append("width=").append(item.width).append('\n')
                append("height=").append(item.height).append('\n')
                append("duration=").append(item.durationMs).append('\n')
                append("uri=").append(item.mediaUri?.toString().orEmpty()).append('\n')
                append("baked=").append(item.filmBaked).append('\n')
            },
        )
        val index = items.indexOfFirst { it.id == item.id }
        if (index >= 0) items[index] = item else items.add(0, item)
    }

    fun remove(item: LibraryItem) {
        items.removeAll { it.id == item.id }
        item.dir.deleteRecursively()
    }

    private fun read(dir: File): LibraryItem? = runCatching {
        val map = File(dir, "meta.txt").readLines().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        LibraryItem(
            id = dir.name,
            kind = MediaKind.valueOf(map.getValue("kind")),
            dir = dir,
            createdMs = map.getValue("created").toLong(),
            width = map.getValue("width").toInt(),
            height = map.getValue("height").toInt(),
            durationMs = map["duration"]?.toLongOrNull() ?: 0L,
            mediaUri = map["uri"]?.takeIf { it.isNotEmpty() }?.let(Uri::parse),
            filmBaked = map["baked"]?.toBooleanStrictOrNull() ?: false,
        )
    }.getOrNull()
}
