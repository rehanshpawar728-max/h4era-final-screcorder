package com.h4era.screenrecorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.nio.ByteBuffer

object Mux {

    private fun pick(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    // Joins the video file and the (optional) audio file into one MP4.
    fun merge(video: File, audio: File?, out: FileDescriptor): Boolean {
        var vEx: MediaExtractor? = null
        var aEx: MediaExtractor? = null
        var mx: MediaMuxer? = null
        try {
            val ve = MediaExtractor()
            vEx = ve
            ve.setDataSource(video.absolutePath)
            val vt = pick(ve, "video/")
            if (vt < 0) return false
            ve.selectTrack(vt)

            var ae: MediaExtractor? = null
            var at = -1
            if (audio != null && audio.exists() && audio.length() > 0L) {
                val e = MediaExtractor()
                aEx = e
                e.setDataSource(audio.absolutePath)
                at = pick(e, "audio/")
                if (at >= 0) {
                    e.selectTrack(at)
                    ae = e
                }
            }

            val m = MediaMuxer(out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mx = m
            val vOut = m.addTrack(ve.getTrackFormat(vt))
            val aOut = if (ae != null) m.addTrack(ae.getTrackFormat(at)) else -1
            m.start()

            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            while (true) {
                val vTime = ve.sampleTime
                val aTime = if (ae != null) ae.sampleTime else -1L
                if (vTime < 0 && aTime < 0) break
                val useVideo = vTime >= 0 && (aTime < 0 || vTime <= aTime)
                val ex: MediaExtractor = if (useVideo) ve else ae!!
                val tr = if (useVideo) vOut else aOut
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                bi.set(0, size, ex.sampleTime, ex.sampleFlags)
                m.writeSampleData(tr, buf, bi)
                ex.advance()
            }
            m.stop()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { vEx?.release() } catch (e: Exception) { }
            try { aEx?.release() } catch (e: Exception) { }
            try { mx?.release() } catch (e: Exception) { }
        }
    }

    // Fallback: copy the video file as-is (no audio).
    fun copyRaw(c: Context, src: File, dst: Uri): Boolean {
        return try {
            val o = c.contentResolver.openOutputStream(dst, "wt") ?: return false
            o.use { os -> FileInputStream(src).use { it.copyTo(os) } }
            true
        } catch (e: Exception) {
            false
        }
    }
}
