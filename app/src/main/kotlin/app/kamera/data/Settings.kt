package app.kamera.data

import android.content.Context
import android.content.SharedPreferences
import app.kamera.film.FilmCatalog
import app.kamera.film.LensFilters
import app.kamera.film.PhotoFormat
import app.kamera.film.VideoFormat

/** Persistent user preferences. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("kamera", Context.MODE_PRIVATE)

    var stockId: String
        get() = prefs.getString("stock", FilmCatalog.default.id) ?: FilmCatalog.default.id
        set(value) = prefs.edit().putString("stock", value).apply()

    var photoFormat: PhotoFormat
        get() = PhotoFormat.fromId(prefs.getString("photoFormat", null))
        set(value) = prefs.edit().putString("photoFormat", value.id).apply()

    var videoFormat: VideoFormat
        get() = VideoFormat.fromId(prefs.getString("videoFormat", null))
        set(value) = prefs.edit().putString("videoFormat", value.id).apply()

    /** Short edge of the video frame: 1080 or 2160. */
    var videoHeight: Int
        get() = prefs.getInt("videoHeight", 1080)
        set(value) = prefs.edit().putInt("videoHeight", value).apply()

    var videoFps: Int
        get() = prefs.getInt("videoFps", 24)
        set(value) = prefs.edit().putInt("videoFps", value).apply()

    /** Filters loaded in the camera's holder. */
    var lensFilters: LensFilters
        get() = LensFilters.decode(prefs.getString("filters", ""))
        set(value) = prefs.edit().putString("filters", value.encode()).apply()

    var showGrid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) = prefs.edit().putBoolean("grid", value).apply()

    var showLevel: Boolean
        get() = prefs.getBoolean("level", true)
        set(value) = prefs.edit().putBoolean("level", value).apply()

    var showHistogram: Boolean
        get() = prefs.getBoolean("histogram", false)
        set(value) = prefs.edit().putBoolean("histogram", value).apply()

    var zebra: Boolean
        get() = prefs.getBoolean("zebra", false)
        set(value) = prefs.edit().putBoolean("zebra", value).apply()

    var peaking: Boolean
        get() = prefs.getBoolean("peaking", true)
        set(value) = prefs.edit().putBoolean("peaking", value).apply()

    var shutterSound: Boolean
        get() = prefs.getBoolean("shutterSound", true)
        set(value) = prefs.edit().putBoolean("shutterSound", value).apply()

    /** Frames exposed on the current roll of a stock; a roll holds 36. */
    fun framesExposed(stockId: String): Int = prefs.getInt("roll_$stockId", 0)

    fun advanceFrame(stockId: String): Int {
        val next = framesExposed(stockId) % ROLL_LENGTH + 1
        prefs.edit().putInt("roll_$stockId", next).apply()
        return next
    }

    companion object {
        const val ROLL_LENGTH = 36
    }
}
