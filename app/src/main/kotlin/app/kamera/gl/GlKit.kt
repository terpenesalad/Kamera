package app.kamera.gl

import android.content.res.AssetManager
import android.opengl.GLES11Ext
import android.opengl.GLES30

/** A linked shader program with cached uniform locations. */
class GlProgram(vertexSource: String, fragmentSource: String, positionAttribute: String? = null) {
    val id: Int = GLES30.glCreateProgram()
    private val locations = HashMap<String, Int>()

    init {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        GLES30.glAttachShader(id, vertex)
        GLES30.glAttachShader(id, fragment)
        positionAttribute?.let { GLES30.glBindAttribLocation(id, 0, it) }
        GLES30.glLinkProgram(id)
        val status = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Program link failed: ${GLES30.glGetProgramInfoLog(id)}" }
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
    }

    fun use() = GLES30.glUseProgram(id)

    fun location(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }

    fun set1i(name: String, v: Int) = GLES30.glUniform1i(location(name), v)
    fun set1f(name: String, v: Float) = GLES30.glUniform1f(location(name), v)
    fun set2f(name: String, x: Float, y: Float) = GLES30.glUniform2f(location(name), x, y)
    fun set3f(name: String, v: FloatArray) = GLES30.glUniform3f(location(name), v[0], v[1], v[2])
    fun setMat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(location(name), 1, false, m, 0)

    fun release() = GLES30.glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }
}

/** Loads the GLSL sources from assets and assembles them for each source type. */
class ShaderLibrary(private val assets: AssetManager) {
    private val sources = HashMap<String, String>()

    private fun read(name: String): String = sources.getOrPut(name) {
        assets.open("shaders/$name").bufferedReader().use { it.readText() }
    }

    fun build(fragment: String, viewfinder: Boolean): GlProgram {
        val vertexHeader = "#version 300 es\nprecision highp float;\nprecision highp int;\n"
        val fragmentHeader = buildString {
            append("#version 300 es\nprecision highp float;\nprecision highp int;\n")
            append("#define SOURCE_SAMPLER highp sampler2D\n")
            if (viewfinder) append("#define VIEWFINDER 1\n")
        }
        return GlProgram(
            vertexHeader + read("fullscreen.vert"),
            fragmentHeader + read("common.glsl") + read(fragment),
        )
    }

    /**
     * Copies a camera or decoder frame into an ordinary texture. Written in GLSL ES 1.00 because
     * the base external-image extension is the one every Android GPU driver supports.
     */
    fun buildExternalResolve(): GlProgram = GlProgram(
        """
        attribute vec2 aPosition;
        varying vec2 vUv;
        void main() {
            vUv = aPosition * 0.5 + 0.5;
            gl_Position = vec4(aPosition, 0.0, 1.0);
        }
        """.trimIndent(),
        """
        #extension GL_OES_EGL_image_external : require
        precision highp float;
        uniform samplerExternalOES uFrame;
        varying vec2 vUv;
        void main() {
            gl_FragColor = vec4(texture2D(uFrame, vUv).rgb, 1.0);
        }
        """.trimIndent(),
        positionAttribute = "aPosition",
    )
}

/** An RGBA8 texture with a framebuffer attached. */
class RenderTarget(val width: Int, val height: Int) {
    val texture: Int = GlTextures.create2D(GLES30.GL_LINEAR, GLES30.GL_CLAMP_TO_EDGE)
    val framebuffer: Int

    init {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
        )
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        check(status == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete: $status (${width}x$height)" }
    }

    fun release() {
        GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }
}

object GlTextures {
    fun create2D(filter: Int, wrap: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        return ids[0]
    }

    fun createExternal(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES30.glBindTexture(target, ids[0])
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    fun delete(id: Int) = GLES30.glDeleteTextures(1, intArrayOf(id), 0)
}
