package com.h4era.screenrecorder

import android.annotation.SuppressLint
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
import java.io.File

// Records microphone and/or internal (playback) audio, encodes it to AAC
// and writes it to an .m4a file. Mixed when both are on.
@SuppressLint("MissingPermission")
class AudioPipeline(
    private val projection: MediaProjection,
    private val wantMic: Boolean,
    private val wantInternal: Boolean,
    private val outFile: File
) {
    companion object {
        const val RATE = 44100
    }

    @Volatile
    var paused = false

    @Volatile
    private var running = false

    var micOk = false
        private set
    var internalOk = false
        private set

    @Volatile
    var wroteAny = false
        private set

    private var micRec: AudioRecord? = null
    private var intRec: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var thread: Thread? = null
    private var track = -1
    private var muxStarted = false
    private var totalFrames = 0L
    private val info = MediaCodec.BufferInfo()

    private fun tryStart(r: AudioRecord?): Boolean {
        if (r == null) return false
        return try {
            r.startRecording()
            r.recordingState == AudioRecord.RECORDSTATE_RECORDING
        } catch (e: Exception) {
            false
        }
    }

    fun start() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = maxOf(minBuf, 4096) * 4

            if (wantMic) {
                try {
                    val r = AudioRecord(
                        MediaRecorder.AudioSource.MIC, RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize
                    )
                    if (r.state == AudioRecord.STATE_INITIALIZED) micRec = r else r.release()
                } catch (e: Exception) {
                }
            }
            if (wantInternal) {
                try {
                    val fmt = AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                    val cfg = AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                    val r = AudioRecord.Builder()
                        .setAudioFormat(fmt)
                        .setBufferSizeInBytes(bufSize)
                        .setAudioPlaybackCaptureConfig(cfg)
                        .build()
                    if (r.state == AudioRecord.STATE_INITIALIZED) intRec = r else r.release()
                } catch (e: Exception) {
                }
            }

            micOk = tryStart(micRec)
            if (!micOk) {
                try { micRec?.release() } catch (e: Exception) { }
                micRec = null
            }
            internalOk = tryStart(intRec)
            if (!internalOk) {
                try { intRec?.release() } catch (e: Exception) { }
                intRec = null
            }
            if (!micOk && !internalOk) return

            val mf = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
            mf.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            mf.setInteger(MediaFormat.KEY_BIT_RATE, 128000)
            mf.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            c.configure(mf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            running = true
            val t = Thread { loop() }
            thread = t
            t.start()
        } catch (e: Exception) {
            running = false
            try { micRec?.release() } catch (x: Exception) { }
            try { intRec?.release() } catch (x: Exception) { }
            try { codec?.release() } catch (x: Exception) { }
            try { muxer?.release() } catch (x: Exception) { }
            micRec = null
            intRec = null
            codec = null
            muxer = null
            micOk = false
            internalOk = false
        }
    }

    private fun loop() {
        val n = 2048
        val a = ShortArray(n)
        val b = ShortArray(n)
        val out = ByteArray(n * 2)
        try {
            while (running) {
                val m = micRec
                val i = intRec
                val ra = if (m != null) m.read(a, 0, n) else 0
                val rb = if (i != null) i.read(b, 0, n) else 0
                val ca = if (ra > 0) ra else 0
                val cb = if (rb > 0) rb else 0
                val count = maxOf(ca, cb)
                if (count <= 0) {
                    Thread.sleep(10)
                    continue
                }
                if (paused) continue
                val mute = RecorderState.muted
                for (k in 0 until count) {
                    var s = 0
                    if (k < ca && !mute) s += a[k].toInt()
                    if (k < cb) s += b[k].toInt()
                    if (s > 32767) s = 32767
                    if (s < -32768) s = -32768
                    out[2 * k] = (s and 0xFF).toByte()
                    out[2 * k + 1] = ((s shr 8) and 0xFF).toByte()
                }
                feed(out, count * 2)
            }
        } catch (e: Exception) {
        }
    }

    private fun feed(data: ByteArray, len: Int) {
        val c = codec ?: return
        var off = 0
        while (off < len && running) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val ib = c.getInputBuffer(idx)
                if (ib != null) {
                    ib.clear()
                    var chunk = minOf(len - off, ib.capacity())
                    chunk -= chunk % 2
                    ib.put(data, off, chunk)
                    val pts = totalFrames * 1_000_000L / RATE
                    c.queueInputBuffer(idx, 0, chunk, pts, 0)
                    totalFrames += (chunk / 2).toLong()
                    off += chunk
                }
            }
            drain(0)
        }
        drain(0)
    }

    // Returns true when the end-of-stream marker was seen.
    private fun drain(waitUs: Long): Boolean {
        val c = codec ?: return true
        while (true) {
            val oi = c.dequeueOutputBuffer(info, waitUs)
            if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val mx = muxer ?: return true
                if (!muxStarted) {
                    track = mx.addTrack(c.outputFormat)
                    mx.start()
                    muxStarted = true
                }
            } else if (oi >= 0) {
                val ob = c.getOutputBuffer(oi)
                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0
                if (ob != null && info.size > 0 && muxStarted) {
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    muxer?.writeSampleData(track, ob, info)
                    wroteAny = true
                }
                c.releaseOutputBuffer(oi, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true
            }
        }
    }

    // Stops everything. Returns true if the audio file has usable data.
    fun stop(): Boolean {
        running = false
        try { thread?.join(2000) } catch (e: Exception) { }
        thread = null
        try { micRec?.stop() } catch (e: Exception) { }
        try { micRec?.release() } catch (e: Exception) { }
        try { intRec?.stop() } catch (e: Exception) { }
        try { intRec?.release() } catch (e: Exception) { }
        micRec = null
        intRec = null

        val c = codec
        if (c != null) {
            try {
                var sent = false
                var tries = 0
                while (!sent && tries < 50) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        c.queueInputBuffer(
                            idx, 0, 0, totalFrames * 1_000_000L / RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sent = true
                    } else {
                        drain(0)
                        tries++
                    }
                }
                var t = 0
                while (t < 50 && !drain(10_000)) t++
            } catch (e: Exception) {
            }
            try { c.stop() } catch (e: Exception) { }
            try { c.release() } catch (e: Exception) { }
        }
        codec = null
        try {
            if (muxStarted) muxer?.stop()
        } catch (e: Exception) {
            wroteAny = false
        }
        try { muxer?.release() } catch (e: Exception) { }
        muxer = null
        return wroteAny
    }
}
