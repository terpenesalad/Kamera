package app.kamera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/** Owns an EGL display and an OpenGL ES 3 context whose surfaces can feed a video encoder. */
class EglCore {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "No suitable EGL config"
        }
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed: ${EGL14.eglGetError()}" }
    }

    /** Creates a surface for an [android.view.Surface] or [android.graphics.SurfaceTexture]. */
    fun createWindowSurface(window: Any): EGLSurface {
        val surface = EGL14.eglCreateWindowSurface(display, config, window, intArrayOf(EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        return surface
    }

    fun createPbuffer(width: Int = 1, height: Int = 1): EGLSurface {
        val attributes = intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
        check(surface != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed: ${EGL14.eglGetError()}" }
        return surface
    }

    fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: ${EGL14.eglGetError()}"
        }
    }

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun setPresentationTime(surface: EGLSurface, nanoseconds: Long) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanoseconds)
    }

    fun releaseSurface(surface: EGLSurface) {
        EGL14.eglDestroySurface(display, surface)
    }

    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
