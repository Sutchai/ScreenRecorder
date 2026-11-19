package com.screenrec

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ท่อบันทึกทั้งหมด:
 *  หน้าจอ -> VirtualDisplay -> H.264 encoder ┐
 *  เสียงภายใน + ไมค์ -> AudioMixer -> AAC ───┴-> MediaMuxer -> MP4
 *
 * เวลา (PTS): วิดีโอใช้เวลาจริงลบช่วง Pause, เสียงใช้ตัวนับ sample (ไม่นับตอน Pause)
 */
class RecordingManager(
    private val ctx: Context,
    private val projection: MediaProjection,
    private val micOn: Boolean,
    private val internalOn: Boolean,
    private val landscape: Boolean,
    private val onProjectionStopped: () -> Unit,
    private val onError: (String) -> Unit,
) {
    companion object {
        const val SAMPLE_RATE = 44100
        const val FPS = 30
        private const val CHUNK = 2048 // samples ต่อรอบ (mono)
    }

    private val storage = StorageManager(ctx)
    private var output: StorageManager.Output? = null
    val outputUri: Uri? get() = output?.uri

    private var muxer: MediaMuxer? = null
    private var vEnc: MediaCodec? = null
    private var aEnc: MediaCodec? = null
    private var vd: VirtualDisplay? = null
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var density = 320

    private var intRec: AudioRecord? = null
    private var micRec: AudioRecord? = null
    private val hasAudio = micOn || internalOn

    private val lock = Object()
    private var vTrack = -1
    private var aTrack = -1
    private var muxerStarted = false
    private class Pending(val track: Int, val data: ByteArray, val info: MediaCodec.BufferInfo)
    private val pending = ArrayList<Pending>()

    @Volatile private var running = false
    @Volatile private var paused = false
    private var baseUs = 0L
    private var pauseStartUs = 0L
    @Volatile private var pausedTotalUs = 0L
    private var lastVideoPts = -1L

    private var vThread: Thread? = null
    private var aThread: Thread? = null

    private fun nowUs() = System.nanoTime() / 1000

    // ---------------------------------------------------------------- start

    fun start() {
        try {
            output = storage.create()
            muxer = MediaMuxer(output!!.pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            setupVideo()
            if (hasAudio) setupAudio()

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { onProjectionStopped() }
            }, Handler(Looper.getMainLooper()))

            vd = projection.createVirtualDisplay(
                "ScreenRecorder", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null
            )

            baseUs = nowUs()
            running = true
            vEnc!!.let { /* started in setupVideo */ }
            vThread = thread(name = "video-drain") { guarded { drainVideo() } }
            if (hasAudio) aThread = thread(name = "audio-loop") { guarded { audioLoop() } }
        } catch (e: Exception) {
            releaseAll()
            output?.let { storage.delete(it) }
            throw e
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (e: Exception) { onError(e.message ?: e.javaClass.simpleName) }
    }

    // ---------------------------------------------------------------- video

    private fun even(f: Float) = (f.roundToInt() / 2) * 2

    private fun setupVideo() {
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
        density = dm.densityDpi
        val longPx = max(dm.widthPixels, dm.heightPixels)
        val shortPx = min(dm.widthPixels, dm.heightPixels)

        var lastErr: Exception? = null
        // ลอง 1080p ก่อน ถ้า encoder ไม่รับ ลด 720p
        for (box in intArrayOf(1080, 720)) {
            val scale = min(1f, min(box * 16f / 9f / longPx, box.toFloat() / shortPx))
            val l = even(longPx * scale)
            val s = even(shortPx * scale)
            val w = if (landscape) l else s
            val h = if (landscape) s else l
            var enc: MediaCodec? = null
            try {
                val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (box == 1080) 8_000_000 else 5_000_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 200_000L)
                }
                enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                surface = enc.createInputSurface()
                enc.start()
                vEnc = enc
                width = w
                height = h
                return
            } catch (e: Exception) {
                lastErr = e
                runCatching { enc?.release() }
                surface = null
            }
        }
        throw lastErr ?: IllegalStateException("สร้าง video encoder ไม่ได้")
    }

    private fun drainVideo() {
        val enc = vEnc!!
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    vTrack = muxer!!.addTrack(enc.outputFormat)
                    tryStartMuxer()
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && !paused) {
                        val pts = info.presentationTimeUs - baseUs - pausedTotalUs
                        if (pts >= 0 && pts > lastVideoPts) {
                            info.presentationTimeUs = pts
                            lastVideoPts = pts
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            writeSample(vTrack, buf, info)
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
            }
        }
    }

    // ---------------------------------------------------------------- audio

    @SuppressLint("MissingPermission")
    private fun setupAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufBytes = max(minBuf, CHUNK * 2) * 2
        val fmt = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        if (internalOn) {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val r = AudioRecord.Builder()
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes)
                .setAudioPlaybackCaptureConfig(cfg)
                .build()
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                throw IllegalStateException("เปิดการจับเสียงภายในไม่ได้")
            }
            intRec = r
        }
        if (micOn) {
            val r = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes
            )
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                throw IllegalStateException("เปิดไมค์ไม่ได้ (อาจถูกแอปอื่นใช้อยู่)")
            }
            micRec = r
        }

        val af = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()
        aEnc = enc
    }

    private fun readFull(r: AudioRecord, dst: ShortArray, n: Int) {
        var off = 0
        while (off < n) {
            val got = r.read(dst, off, n - off)
            if (got <= 0) { java.util.Arrays.fill(dst, off, n, 0); return }
            off += got
        }
    }

    private fun audioLoop() {
        val enc = aEnc!!
        val info = MediaCodec.BufferInfo()
        val a = ShortArray(CHUNK)
        val b = ShortArray(CHUNK)
        val m = ShortArray(CHUNK)
        var samples = 0L
        var active = false

        while (running) {
            if (paused) {
                if (active) {
                    runCatching { intRec?.stop() }; runCatching { micRec?.stop() }
                    active = false
                }
                drainAudio(enc, info, false)
                Thread.sleep(20)
                continue
            }
            if (!active) {
                intRec?.startRecording(); micRec?.startRecording()
                active = true
            }
            intRec?.let { readFull(it, a, CHUNK) }
            micRec?.let { readFull(it, b, CHUNK) }
            AudioMixer.mix(intRec?.let { a }, micRec?.let { b }, m, CHUNK)
            feedAudio(enc, info, m, CHUNK, samples * 1_000_000L / SAMPLE_RATE, false)
            samples += CHUNK
            drainAudio(enc, info, false)
        }

        runCatching { intRec?.stop() }; runCatching { micRec?.stop() }
        runCatching { intRec?.release() }; runCatching { micRec?.release() }
        feedAudio(enc, info, m, 0, samples * 1_000_000L / SAMPLE_RATE, true)
        drainAudio(enc, info, true)
    }

    private fun feedAudio(
        enc: MediaCodec, info: MediaCodec.BufferInfo,
        data: ShortArray, n: Int, ptsUs: Long, eos: Boolean
    ) {
        while (true) {
            val i = enc.dequeueInputBuffer(10_000)
            if (i >= 0) {
                val ib = enc.getInputBuffer(i)!!
                ib.clear()
                if (n > 0) ib.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(data, 0, n)
                enc.queueInputBuffer(
                    i, 0, n * 2, ptsUs,
                    if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                )
                return
            }
            drainAudio(enc, info, false)
        }
    }

    private fun drainAudio(enc: MediaCodec, info: MediaCodec.BufferInfo, untilEos: Boolean) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (untilEos) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(lock) {
                    aTrack = muxer!!.addTrack(enc.outputFormat)
                    tryStartMuxer()
                }
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        writeSample(aTrack, buf, info)
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (eos) return
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEos) return
            }
        }
    }

    // ---------------------------------------------------------------- muxer

    /** เรียกภายใน synchronized(lock) เท่านั้น */
    private fun tryStartMuxer() {
        if (muxerStarted) return
        if (vTrack >= 0 && (!hasAudio || aTrack >= 0)) {
            muxer!!.start()
            muxerStarted = true
            for (p in pending) {
                runCatching { muxer!!.writeSampleData(p.track, ByteBuffer.wrap(p.data), p.info) }
            }
            pending.clear()
        }
    }

    private fun writeSample(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (muxerStarted) {
                runCatching { muxer!!.writeSampleData(track, buf, info) }
            } else {
                val bytes = ByteArray(info.size)
                buf.get(bytes)
                val copy = MediaCodec.BufferInfo().apply {
                    set(0, info.size, info.presentationTimeUs, info.flags)
                }
                pending.add(Pending(track, bytes, copy))
            }
        }
    }

    // ---------------------------------------------------------------- control

    fun pause() {
        if (!running || paused) return
        pauseStartUs = nowUs()
        paused = true
        runCatching { vd?.surface = null }
    }

    fun resume() {
        if (!running || !paused) return
        pausedTotalUs += nowUs() - pauseStartUs
        runCatching { vd?.surface = surface }
        paused = false
    }

    /** เรียกจาก background thread (มี join) */
    fun stop() {
        if (!running) { releaseAll(); return }
        running = false
        runCatching { vd?.release() }
        runCatching { vEnc?.signalEndOfInputStream() }
        aThread?.join(5000)
        vThread?.join(5000)

        var ok = false
        synchronized(lock) {
            if (muxerStarted) ok = runCatching { muxer?.stop() }.isSuccess
        }
        releaseAll()
        output?.let { if (ok) storage.finish(it) else storage.delete(it) }
    }

    private fun releaseAll() {
        runCatching { vEnc?.stop() }; runCatching { vEnc?.release() }
        runCatching { aEnc?.stop() }; runCatching { aEnc?.release() }
        runCatching { surface?.release() }
        runCatching { muxer?.release() }
        runCatching { intRec?.release() }; runCatching { micRec?.release() }
        runCatching { projection.stop() }
        vEnc = null; aEnc = null; surface = null; muxer = null
    }
}
