package com.h4era.screenrecorder

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.text.format.Formatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

object ToolState {
    var busy by mutableStateOf(false)
    var title by mutableStateOf("")
    var progress by mutableStateOf(-1)
    var cancellable by mutableStateOf(false)
    var result by mutableStateOf<String?>(null)
    var resultUri by mutableStateOf<Uri?>(null)
    var transformer: Transformer? = null
    var tmp: File? = null
}

object VideoTools {

    private fun baseName(name: String): String {
        return name.removeSuffix(".mp4").removeSuffix(".MP4")
    }

    private fun pendingUri(c: Context, name: String): Uri? {
        val v = ContentValues()
        v.put(MediaStore.Video.Media.DISPLAY_NAME, name)
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/H4ERA Screen Recorder/")
        v.put(MediaStore.Video.Media.IS_PENDING, 1)
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return c.contentResolver.insert(collection, v)
    }

    private fun copyToStore(c: Context, f: File, name: String): Uri? {
        val u = pendingUri(c, name) ?: return null
        return try {
            val os = c.contentResolver.openOutputStream(u, "wt") ?: throw Exception("no stream")
            os.use { out -> FileInputStream(f).use { it.copyTo(out) } }
            MediaStoreManager.publish(c, u)
            u
        } catch (e: Exception) {
            MediaStoreManager.discard(c, u)
            null
        }
    }

    // ---------- TRIM (no re-encoding) ----------
    // Blocking: call from a background thread.
    fun trim(c: Context, src: Uri, name: String, startMs: Long, endMs: Long): Uri? {
        val u = pendingUri(c, baseName(name) + "_trim.mp4") ?: return null
        val ex = MediaExtractor()
        var pfd: ParcelFileDescriptor? = null
        var mux: MediaMuxer? = null
        var ok = false
        try {
            ex.setDataSource(c, src, null)
            var vIdx = -1
            val aIdx = ArrayList<Int>()
            for (i in 0 until ex.trackCount) {
                val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/") && vIdx < 0) {
                    vIdx = i
                } else if (mime.startsWith("audio/")) {
                    aIdx.add(i)
                }
            }
            if (vIdx < 0) throw Exception("no video track")

            var rot = 0
            try {
                val mr = MediaMetadataRetriever()
                mr.setDataSource(c, src)
                rot = mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
                mr.release()
            } catch (e: Exception) {
            }

            ex.selectTrack(vIdx)
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = ex.sampleTime
            if (base < 0) throw Exception("cannot seek")
            for (a in aIdx) ex.selectTrack(a)
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val p = c.contentResolver.openFileDescriptor(u, "rw") ?: throw Exception("no file")
            pfd = p
            val m = MediaMuxer(p.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mux = m
            val map = HashMap<Int, Int>()
            map[vIdx] = m.addTrack(ex.getTrackFormat(vIdx))
            for (a in aIdx) {
                map[a] = m.addTrack(ex.getTrackFormat(a))
            }
            if (rot != 0) m.setOrientationHint(rot)
            m.start()

            val endUs = endMs * 1000
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val bi = MediaCodec.BufferInfo()
            var wrote = 0
            while (true) {
                val ti = ex.sampleTrackIndex
                if (ti < 0) break
                val t = ex.sampleTime
                if (t > endUs) break
                if (t < base) {
                    ex.advance()
                    continue
                }
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                bi.set(0, size, t - base, ex.sampleFlags)
                val tr = map[ti]
                if (tr != null) {
                    m.writeSampleData(tr, buf, bi)
                    wrote++
                }
                ex.advance()
            }
            if (wrote == 0) throw Exception("nothing written")
            m.stop()
            ok = true
        } catch (e: Exception) {
            ok = false
        } finally {
            try { ex.release() } catch (e: Exception) { }
            try { mux?.release() } catch (e: Exception) { }
            try { pfd?.close() } catch (e: Exception) { }
        }
        return if (ok) {
            MediaStoreManager.publish(c, u)
            u
        } else {
            MediaStoreManager.discard(c, u)
            null
        }
    }

    // ---------- COMPRESS (re-encodes to a lower bitrate) ----------
    // Must be called from the main thread.
    fun compress(c: Context, src: Uri, name: String, bitrate: Int) {
        if (ToolState.busy) return
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        val tmp = File(app.cacheDir, "h4era_small.mp4")
        if (tmp.exists()) tmp.delete()
        val origSize = MediaStoreManager.info(app, src).second

        ToolState.busy = true
        ToolState.title = "Compressing..."
        ToolState.progress = 0
        ToolState.cancellable = true
        ToolState.result = null
        ToolState.resultUri = null
        ToolState.tmp = tmp

        fun fail(msg: String) {
            tmp.delete()
            ToolState.transformer = null
            ToolState.cancellable = false
            ToolState.busy = false
            ToolState.result = msg
        }

        try {
            val enc = DefaultEncoderFactory.Builder(app)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(bitrate).build()
                )
                .build()

            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    ToolState.transformer = null
                    ToolState.cancellable = false
                    ToolState.title = "Saving..."
                    ToolState.progress = -1
                    Thread {
                        val u = copyToStore(app, tmp, baseName(name) + "_small.mp4")
                        tmp.delete()
                        main.post {
                            if (u == null) {
                                fail("Could not save the compressed video.")
                            } else {
                                val newSize = MediaStoreManager.info(app, u).second
                                if (origSize > 0 && newSize >= origSize) {
                                    MediaStoreManager.discard(app, u)
                                    ToolState.busy = false
                                    ToolState.result = "The new copy was not smaller than the original, " +
                                            "so it was removed. This video is already compressed."
                                } else {
                                    val pct = if (origSize > 0)
                                        ((1.0 - newSize.toDouble() / origSize) * 100).toInt() else 0
                                    ToolState.busy = false
                                    ToolState.resultUri = u
                                    ToolState.result = "Saved a smaller copy.\n" +
                                            Formatter.formatShortFileSize(app, origSize) + " -> " +
                                            Formatter.formatShortFileSize(app, newSize) +
                                            " (about " + pct + "% smaller)\nThe original was kept."
                                    RecorderState.libraryVersion++
                                }
                            }
                        }
                    }.start()
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    main.post { fail("Compression failed: " + exportException.message) }
                }
            }

            val tr = Transformer.Builder(app)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setEncoderFactory(enc)
                .addListener(listener)
                .build()
            ToolState.transformer = tr
            tr.start(MediaItem.fromUri(src), tmp.absolutePath)

            val holder = ProgressHolder()
            val poll = object : Runnable {
                override fun run() {
                    val t = ToolState.transformer
                    if (!ToolState.busy || t == null) return
                    try {
                        if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                            ToolState.progress = holder.progress
                        }
                    } catch (e: Exception) {
                    }
                    main.postDelayed(this, 300)
                }
            }
            main.post(poll)
        } catch (e: Exception) {
            fail("Could not start compression: " + e.message)
        }
    }

    fun cancel() {
        try { ToolState.transformer?.cancel() } catch (e: Exception) { }
        ToolState.transformer = null
        ToolState.tmp?.delete()
        ToolState.cancellable = false
        ToolState.busy = false
        ToolState.result = "Compression cancelled."
    }
}
