package app.kamera

import android.app.Application
import android.content.Context
import app.kamera.data.Library
import app.kamera.data.MediaStoreWriter
import app.kamera.data.Settings
import app.kamera.render.PhotoDeveloper

/** Holds the long-lived services shared by every screen. */
class KameraApp : Application() {
    val settings: Settings by lazy { Settings(this) }
    val library: Library by lazy { Library(this) }
    val mediaWriter: MediaStoreWriter by lazy { MediaStoreWriter(this) }
    val developer: PhotoDeveloper by lazy { PhotoDeveloper(this, library, mediaWriter) }
}

val Context.kamera: KameraApp get() = applicationContext as KameraApp
