package app.kamera.render

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import app.kamera.data.MediaStoreWriter
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/** Encodes the developed picture and microphone audio into an MP4 in the shared collection. */
class VideoRecorder(
    private val writer: MediaStoreWriter,
    val width: Int,
    val height: Int,
    private val fps: Int,
    private val withAudio: Boolean,
) {
    class Result(val uri: Uri, val width: Int, val height: Int, val durationMs: Long)

    private val pending = writer.beginVideo()
    private val muxer = MediaMuxer(pending.descriptor.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val videoThread = HandlerThread("VideoEncoder").apply { start() }

    private val videoCodec: MediaCodec
    val inputSurface: Surface

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    private val backlog = ArrayList<Sample>()
    private var baseUs = -1L
    private var lastVideoUs = 0L
    private var lastAudioUs = -1L
    private var videoDone = false
    private var audioDone = !withAudio
    private var finished = false
    private var onStopped: ((Result?) -> Unit)? = null

    private val stopAudio = AtomicBoolean(false)
    private var audioThread: Thread? = null

    private class Sample(val track: Int, val data: ByteArray, val ptsUs: Long, val flags: Int)

    init {
        val (codec, format) = createVideoEncoder()
        videoCodec = codec
        codec.setCallback(
            object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(c: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        writeSample(TRACK_VIDEO, buffer, info)
                    }
                    c.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) markVideoDone()
                }

                override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "Video encoder failed", e)
                    markVideoDone()
                }

                override fun onOutputFormatChanged(c: MediaCodec, outputFormat: MediaFormat) {
                    synchronized(lock) {
                        videoTrack = muxer.addTrack(outputFormat)
                        startMuxerIfReady()
                    }
                }
            },
            Handler(videoThread.looper),
        )
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
    }

    private fun createVideoEncoder(): Pair<MediaCodec, MediaFormat> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        for ((mime, density) in listOf(MediaFormat.MIMETYPE_VIDEO_HEVC to 0.22f, MediaFormat.MIMETYPE_VIDEO_AVC to 0.34f)) {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // Grain is incompressible, so film footage needs far more bitrate than clean video.
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * fps * density).toInt().coerceIn(8_000_000, 150_000_000))
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
                setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
                setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            }
            val name = list.findEncoderForFormat(format) ?: continue
            return MediaCodec.createByCodecName(name) to format
        }
        throw IllegalStateException("No video encoder supports ${width}x$height at $fps fps")
    }

    fun start() {
        videoCodec.start()
        if (withAudio) audioThread = Thread(::runAudio, "AudioEncoder").apply { start() }
    }

    private fun startMuxerIfReady() {
        if (muxerStarted || videoTrack < 0 || (withAudio && audioTrack < 0 && !audioDone)) return
        muxer.start()
        muxerStarted = true
        for (sample in backlog) write(sample.track, ByteBuffer.wrap(sample.data), sample.data.size, sample.ptsUs, sample.flags)
        backlog.clear()
    }

    private fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        synchronized(lock) {
            if (muxerStarted) {
                write(track, buffer, info.size, info.presentationTimeUs, info.flags)
            } else {
                val copy = ByteArray(info.size)
                buffer.get(copy)
                backlog.add(Sample(track, copy, info.presentationTimeUs, info.flags))
            }
        }
    }

    private fun write(track: Int, buffer: ByteBuffer, size: Int, ptsUs: Long, flags: Int) {
        val index = if (track == TRACK_VIDEO) videoTrack else audioTrack
        if (index < 0) return
        if (baseUs < 0) {
            if (track != TRACK_VIDEO) return
            baseUs = ptsUs
        }
        val relative = ptsUs - baseUs
        if (relative < 0) return
        if (track == TRACK_VIDEO) {
            lastVideoUs = max(lastVideoUs, relative)
        } else {
            if (relative <= lastAudioUs) return
            lastAudioUs = relative
        }
        val info = MediaCodec.BufferInfo().apply {
            set(0, size, relative, flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
        }
        val slice = buffer.slice()
        muxer.writeSampleData(index, slice, info)
    }

    @SuppressLint("MissingPermission")
    private fun runAudio() {
        var record: AudioRecord? = null
        var encoder: MediaCodec? = null
        try {
            val channelMask = AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                SAMPLE_RATE,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer * 4, 64 * 1024),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable" }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 256_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            record.startRecording()

            val info = MediaCodec.BufferInfo()
            var framesRead = 0L
            var startNs = -1L
            var inputEnded = false
            var outputEnded = false
            while (!outputEnded) {
                if (!inputEnded) {
                    val index = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (index >= 0) {
                        val input = requireNotNull(encoder.getInputBuffer(index))
                        input.clear()
                        val read = record.read(input, input.capacity().coerceAtMost(8192))
                        val bytes = max(read, 0)
                        val frames = bytes / (CHANNELS * 2)
                        if (startNs < 0 && frames > 0) startNs = System.nanoTime() - frames * 1_000_000_000L / SAMPLE_RATE
                        val ptsUs = max(startNs, 0L) / 1000L + framesRead * 1_000_000L / SAMPLE_RATE
                        framesRead += frames
                        inputEnded = stopAudio.get()
                        val flags = if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        encoder.queueInputBuffer(index, 0, bytes, ptsUs, flags)
                    }
                }
                var index = encoder.dequeueOutputBuffer(info, if (inputEnded) CODEC_TIMEOUT_US else 0L)
                while (index != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        synchronized(lock) {
                            audioTrack = muxer.addTrack(encoder.outputFormat)
                            startMuxerIfReady()
                        }
                    } else if (index >= 0) {
                        val output = encoder.getOutputBuffer(index)
                        if (output != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            writeSample(TRACK_AUDIO, output, info)
                        }
                        encoder.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputEnded = true
                            break
                        }
                    }
                    index = encoder.dequeueOutputBuffer(info, 0L)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio recording stopped", e)
        } finally {
            runCatching { record?.stop() }
            record?.release()
            runCatching { encoder?.stop() }
            encoder?.release()
            markAudioDone()
        }
    }

    fun stop(onResult: (Result?) -> Unit) {
        synchronized(lock) { onStopped = onResult }
        stopAudio.set(true)
        runCatching { videoCodec.signalEndOfInputStream() }.onFailure { markVideoDone() }
    }

    private fun markVideoDone() {
        synchronized(lock) { videoDone = true }
        finishIfDone()
    }

    private fun markAudioDone() {
        synchronized(lock) {
            audioDone = true
            startMuxerIfReady()
        }
        finishIfDone()
    }

    private fun finishIfDone() {
        val callback: ((Result?) -> Unit)?
        val result: Result?
        synchronized(lock) {
            if (finished || !videoDone || !audioDone) return
            finished = true
            callback = onStopped
            result = finalizeFile()
        }
        // This can run inside the codec's own callback, where stopping it may deadlock.
        Thread({
            runCatching { videoCodec.stop() }
            videoCodec.release()
            inputSurface.release()
            videoThread.quitSafely()
            callback?.invoke(result)
        }, "RecorderCleanup").start()
    }

    private fun finalizeFile(): Result? {
        val wroteSomething = muxerStarted && baseUs >= 0
        val stopped = wroteSomething && runCatching { muxer.stop() }.isSuccess
        runCatching { muxer.release() }
        return if (stopped) {
            writer.finishVideo(pending)
            Result(pending.uri, width, height, lastVideoUs / 1000L)
        } else {
            writer.discardVideo(pending)
            null
        }
    }

    private companion object {
        const val TAG = "KameraRecorder"
        const val TRACK_VIDEO = 0
        const val TRACK_AUDIO = 1
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CODEC_TIMEOUT_US = 10_000L
    }
}
