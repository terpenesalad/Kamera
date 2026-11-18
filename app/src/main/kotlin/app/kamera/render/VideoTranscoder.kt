package app.kamera.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.kamera.data.MediaStoreWriter
import app.kamera.film.Affine
import app.kamera.film.FilmCatalog
import app.kamera.film.FilmUniforms
import app.kamera.film.FrameGeometry
import app.kamera.film.Recipe
import app.kamera.gl.EglCore
import app.kamera.gl.FilmEngine
import app.kamera.gl.GlTextures
import app.kamera.gl.GlowBuffers
import app.kamera.gl.OutputTarget
import app.kamera.gl.SourceFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Re-renders a clip through the film engine, applying a trim and a recipe. */
class VideoTranscoder(
    private val context: Context,
    private val writer: MediaStoreWriter,
    private val source: Uri,
    private val trimStartUs: Long,
    private val trimEndUs: Long,
    private val recipe: Recipe,
) {
    class Result(val uri: Uri, val width: Int, val height: Int, val durationMs: Long)

    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    /** Runs to completion on the calling thread. Returns null if cancelled or failed. */
    fun run(onProgress: (Float) -> Unit): Result? {
        val pending = writer.beginVideo()
        return try {
            val result = transcode(pending, onProgress)
            if (result == null) writer.discardVideo(pending) else writer.finishVideo(pending)
            result
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            writer.discardVideo(pending)
            null
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return -1
    }

    private fun transcode(pending: MediaStoreWriter.PendingVideo, onProgress: (Float) -> Unit): Result? {
        val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        val track = findTrack(extractor, "video/")
        check(track >= 0) { "No video track" }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val rotation = if (inputFormat.containsKey(MediaFormat.KEY_ROTATION)) inputFormat.getInteger(MediaFormat.KEY_ROTATION) else 0
        val codedW = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val codedH = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val displayW = if (rotation % 180 == 0) codedW else codedH
        val displayH = if (rotation % 180 == 0) codedH else codedW
        val fps = if (inputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) else 30
        val (outW, outH) = FrameGeometry.outputSize(displayW, displayH, recipe.framing)

        val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * fps * 0.34f).toInt().coerceIn(8_000_000, 150_000_000))
        }
        val encoderName = requireNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(encoderFormat)) {
            "No encoder for ${outW}x$outH"
        }
        val encoder = MediaCodec.createByCodecName(encoderName)
        encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val encoderInput = encoder.createInputSurface()

        val egl = EglCore()
        val eglSurface = egl.createWindowSurface(encoderInput)
        egl.makeCurrent(eglSurface)
        val film = FilmEngine(context.assets)
        val stock = FilmCatalog.find(recipe.stockId)
        film.uploadLut(LutStore.get(stock, recipe.grade))
        val glow = GlowBuffers()
        val uniforms = FilmUniforms.compute(stock, recipe, outW, outH)

        val oes = GlTextures.createExternal()
        val texture = SurfaceTexture(oes).apply { setDefaultBufferSize(codedW, codedH) }
        val frameLock = Object()
        var frameReady = false
        val waiter = HandlerThread("TranscodeFrames").apply { start() }
        texture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameReady = true
                frameLock.notifyAll()
            }
        }, Handler(waiter.looper))
        val decoderSurface = Surface(texture)
        val decoder = MediaCodec.createDecoderByType(requireNotNull(inputFormat.getString(MediaFormat.KEY_MIME)))
        decoder.configure(inputFormat, decoderSurface, null, 0)

        val muxer = MediaMuxer(pending.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val audio = AudioPump(muxer)

        encoder.start()
        decoder.start()
        extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val transform = FloatArray(16)
        val info = MediaCodec.BufferInfo()
        val encoded = MediaCodec.BufferInfo()
        var videoTrack = -1
        var muxerStarted = false
        var inputDone = false
        var decoderDone = false
        var encoderDone = false
        var lastPtsUs = 0L
        var grainState = 0x51ED27
        val span = max(trimEndUs - trimStartUs, 1L)
        var success = false

        fun drainEncoder(endOfStream: Boolean) {
            while (!encoderDone) {
                val index = encoder.dequeueOutputBuffer(encoded, if (endOfStream) CODEC_TIMEOUT_US else 0L)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audio.addTrack()
                        muxer.start()
                        muxerStarted = true
                    }
                    index >= 0 -> {
                        val buffer: ByteBuffer? = encoder.getOutputBuffer(index)
                        if (buffer != null && muxerStarted && encoded.size > 0 &&
                            encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            muxer.writeSampleData(videoTrack, buffer, encoded)
                            lastPtsUs = max(lastPtsUs, encoded.presentationTimeUs)
                            audio.writeUntil(encoded.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
        }

        try {
            while (!decoderDone && !cancelled.get()) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(index))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0 || extractor.sampleTime > trimEndUs) {
                            decoder.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)
                if (index >= 0) {
                    val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val wanted = info.size > 0 && info.presentationTimeUs in trimStartUs..trimEndUs
                    decoder.releaseOutputBuffer(index, wanted)
                    if (wanted) {
                        synchronized(frameLock) {
                            val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
                            while (!frameReady && System.currentTimeMillis() < deadline) frameLock.wait(100L)
                            check(frameReady) { "Timed out waiting for a decoded frame" }
                            frameReady = false
                        }
                        texture.updateTexImage()
                        texture.getTransformMatrix(transform)
                        val map = Affine.fromMat4(transform) *
                            FrameGeometry.frameToSource(displayW.toFloat() / displayH, outW.toFloat() / outH, recipe.framing)
                        val resolved = film.resolveExternal(oes, codedW, codedH)
                        val frame = SourceFrame(resolved, codedW, codedH, map)
                        film.updateGlow(glow, frame, uniforms, outW.toFloat() / outH)
                        grainState = grainState * 1103515245 + 12345
                        val gx = ((grainState ushr 8) and 0xFFF) / 4096f
                        grainState = grainState * 1103515245 + 12345
                        val gy = ((grainState ushr 8) and 0xFFF) / 4096f
                        film.drawFilm(frame, glow, uniforms, OutputTarget(0, 0, 0, outW, outH), gx, gy)
                        egl.setPresentationTime(eglSurface, (info.presentationTimeUs - trimStartUs) * 1000L)
                        egl.swapBuffers(eglSurface)
                        drainEncoder(endOfStream = false)
                        onProgress(((info.presentationTimeUs - trimStartUs).toFloat() / span).coerceIn(0f, 1f))
                    }
                    if (ended) decoderDone = true
                }
            }
            if (!cancelled.get()) {
                encoder.signalEndOfInputStream()
                drainEncoder(endOfStream = true)
                audio.writeUntil(Long.MAX_VALUE)
                success = muxerStarted
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            runCatching { encoder.stop() }
            encoder.release()
            extractor.release()
            audio.release()
            if (muxerStarted) runCatching { muxer.stop() }.onFailure { success = false }
            runCatching { muxer.release() }
            texture.release()
            decoderSurface.release()
            waiter.quitSafely()
            glow.release()
            GlTextures.delete(oes)
            film.release()
            egl.releaseSurface(eglSurface)
            encoderInput.release()
            egl.release()
        }
        return if (success) Result(pending.uri, outW, outH, lastPtsUs / 1000L) else null
    }

    /** Copies the source's compressed audio across, interleaved with the video as it is written. */
    private inner class AudioPump(private val muxer: MediaMuxer) {
        private val extractor = MediaExtractor().apply { setDataSource(context, source, null) }
        private val sourceTrack = findTrack(extractor, "audio/")
        private var track = -1
        private var finished = sourceTrack < 0
        private val buffer: ByteBuffer = ByteBuffer.allocateDirect(1024 * 1024)
        private val info = MediaCodec.BufferInfo()

        init {
            if (sourceTrack >= 0) {
                extractor.selectTrack(sourceTrack)
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }
        }

        fun addTrack() {
            if (sourceTrack >= 0) track = muxer.addTrack(extractor.getTrackFormat(sourceTrack))
        }

        fun writeUntil(outputPtsUs: Long) {
            while (!finished && track >= 0) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime > trimEndUs) {
                    finished = true
                    return
                }
                val relative = sampleTime - trimStartUs
                if (relative > outputPtsUs) return
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    finished = true
                    return
                }
                if (relative >= 0) {
                    info.set(0, size, relative, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(track, buffer, info)
                }
                extractor.advance()
            }
        }

        fun release() = extractor.release()
    }

    private companion object {
        const val TAG = "KameraExport"
        const val CODEC_TIMEOUT_US = 10_000L
        const val FRAME_TIMEOUT_MS = 2500L
    }
}
