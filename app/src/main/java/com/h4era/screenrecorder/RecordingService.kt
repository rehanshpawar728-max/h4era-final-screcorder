package com.h4era.screenrecorder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.text.format.Formatter
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File

class RecordingService : Service() {

    private var projection: MediaProjection? = null
    private var recorder: MediaRecorder? = null
    private var vdisplay: VirtualDisplay? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null
    private var pipeline: AudioPipeline? = null
    private var tmpVideo: File? = null
    private var tmpAudio: File? = null
    private var active = false
    private var paused = false
    private var counting = false
    private var pendingMode = 0
    private var audioDenied = false
    private var cdLeft = 0
    private var cdView: TextView? = null
    private var segmentStart = 0L
    private var accumulated = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            if (active) {
                RecorderState.elapsedSec = currentMs() / 1000
                handler.postDelayed(this, 500)
            }
        }
    }

    private val cdTick = object : Runnable {
        override fun run() {
            if (!counting) return
            if (cdLeft > 0) {
                RecorderState.countdown = cdLeft
                showCd(cdLeft)
                getSystemService(NotificationManager::class.java).notify(1, buildNotification())
                cdLeft--
                handler.postDelayed(this, 1000)
            } else {
                hideCd()
                RecorderState.countdown = -1
                handler.postDelayed(startRunnable, 600)
            }
        }
    }

    private val startRunnable = Runnable {
        if (counting) {
            counting = false
            try {
                begin(pendingMode)
            } catch (e: Exception) {
                fail("Could not start recording: " + e.message)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentMs(): Long {
        return accumulated + (if (paused) 0L else SystemClock.elapsedRealtime() - segmentStart)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            if (active) finish()
            else if (counting) cancelCountdown()
            else if (!RecorderState.saving) stopSelf()
            return START_NOT_STICKY
        }
        if (action == "TOGGLE") {
            if (active) {
                if (paused) resume() else pause()
            } else if (!counting && !RecorderState.saving) {
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (action == "MUTE") {
            if (active) {
                RecorderState.muted = !RecorderState.muted
                getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            } else if (!counting && !RecorderState.saving) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")

        val mode = Prefs.audioMode(this)
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val effective = if (mode != 0 && granted) mode else 0
        audioDenied = mode != 0 && !granted

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        if (effective != 0) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        startForeground(1, buildNotification(), type)

        if (active || counting) return START_NOT_STICKY // prevent duplicate sessions

        if (data == null) {
            fail("Missing screen-capture permission data")
            return START_NOT_STICKY
        }
        try {
            prepareProjection(code, data)
            pendingMode = effective
            if (Prefs.countdown(this)) startCountdown(3) else begin(effective)
        } catch (e: Exception) {
            fail("Could not start recording: " + e.message)
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("rec", "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        fun act(a: String, req: Int): PendingIntent {
            return PendingIntent.getService(
                this, req,
                Intent(this, RecordingService::class.java).setAction(a),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val cd = RecorderState.countdown
        val saving = RecorderState.saving
        val title = if (saving) "H4ERA - saving video"
        else if (cd > 0) "H4ERA - starting in $cd"
        else if (paused) "H4ERA - paused" else "H4ERA - recording"
        val b = Notification.Builder(this, "rec")
            .setContentTitle(title)
            .setContentText(if (saving) "Please wait..." else "Use the buttons below to control recording")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
        if (!counting && !saving) {
            b.addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "Resume" else "Pause",
                act("TOGGLE", 1)
            )
        }
        if (!counting && !saving && active && RecorderState.micActive) {
            b.addAction(
                android.R.drawable.ic_lock_silent_mode,
                if (RecorderState.muted) "Unmute" else "Mute",
                act("MUTE", 3)
            )
        }
        if (!saving) {
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", act("STOP", 2))
        }
        return b.build()
    }

    // ---------- countdown ----------

    private fun startCountdown(secs: Int) {
        counting = true
        cdLeft = secs
        RecorderState.message = null
        RecorderState.countdown = secs
        handler.post(cdTick)
    }

    private fun cancelCountdown() {
        counting = false
        handler.removeCallbacks(cdTick)
        handler.removeCallbacks(startRunnable)
        hideCd()
        release()
        RecorderState.countdown = 0
        RecorderState.message = "Recording cancelled."
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showCd(n: Int) {
        if (!Settings.canDrawOverlays(this)) return
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            var v = cdView
            if (v == null) {
                val t = TextView(this)
                t.textSize = 110f
                t.setTextColor(Color.WHITE)
                t.setShadowLayer(16f, 0f, 0f, Color.BLACK)
                t.gravity = Gravity.CENTER
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.CENTER
                wm.addView(t, lp)
                cdView = t
                v = t
            }
            v.text = n.toString()
        } catch (e: Exception) {
        }
    }

    private fun hideCd() {
        val v = cdView
        cdView = null
        if (v != null) {
            try {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (e: Exception) {
            }
        }
    }

    // ---------- recording ----------

    private fun supported(w: Int, h: Int, fps: Int): Boolean {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder &&
                        info.supportedTypes.any { it.equals("video/avc", true) } &&
                        runCatching {
                            info.getCapabilitiesForType("video/avc").videoCapabilities
                                .areSizeAndRateSupported(w, h, fps.toDouble())
                        }.getOrDefault(false)
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun prepareProjection(code: Int, data: Intent) {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (active) finish() else if (counting) cancelCountdown()
            }
        }, handler)
    }

    private fun begin(mode: Int) {
        val p = projection ?: throw Exception("No screen-capture session")

        val free = Environment.getExternalStorageDirectory().usableSpace
        if (free < 200L * 1024 * 1024) {
            throw Exception("Not enough storage (need at least 200 MB free)")
        }

        val dm = DisplayMetrics()
        val dmgr = getSystemService(DISPLAY_SERVICE) as DisplayManager
        dmgr.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)

        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        val target = Prefs.height(this)
        var scale = if (target <= 0 || target >= shortSide) 1.0 else target.toDouble() / shortSide
        var fps = Prefs.fps(this)

        fun even(v: Double): Int = (v.toInt() / 2) * 2
        var w = even(dm.widthPixels * scale)
        var h = even(dm.heightPixels * scale)

        if (!supported(w, h, fps) && fps > 30 && supported(w, h, 30)) fps = 30
        var guard = 0
        while (!supported(w, h, fps) && guard < 12) {
            scale *= 0.85
            w = even(dm.widthPixels * scale)
            h = even(dm.heightPixels * scale)
            guard++
        }

        val wantMic = mode == 1 || mode == 3
        val wantInt = mode == 2 || mode == 3
        val useAudio = wantMic || wantInt

        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        recorder = r
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (useAudio) {
            val vf = File(cacheDir, "h4era_video.mp4")
            val af = File(cacheDir, "h4era_audio.m4a")
            if (vf.exists()) vf.delete()
            if (af.exists()) af.delete()
            tmpVideo = vf
            tmpAudio = af
            r.setOutputFile(vf.absolutePath)
        } else {
            val u = MediaStoreManager.createPending(this) ?: throw Exception("Cannot create file in Movies")
            uri = u
            pfd = contentResolver.openFileDescriptor(u, "rw")
            r.setOutputFile(pfd!!.fileDescriptor)
        }
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        r.setVideoSize(w, h)
        r.setVideoFrameRate(fps)
        r.setVideoEncodingBitRate(Prefs.mbps(this) * 1_000_000)
        r.setOnErrorListener { _, _, _ ->
            handler.post { if (active) finish() }
        }
        r.prepare()

        vdisplay = p.createVirtualDisplay(
            "H4ERA", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )

        var audioTxt = ""
        RecorderState.micActive = false
        RecorderState.muted = false
        if (useAudio) {
            val pl = AudioPipeline(p, wantMic, wantInt, tmpAudio!!)
            pl.start()
            pipeline = pl
            RecorderState.micActive = pl.micOk
            val parts = ArrayList<String>()
            if (pl.micOk) parts.add("Mic")
            if (pl.internalOk) parts.add("Internal")
            audioTxt = if (parts.isEmpty()) " • No audio (source unavailable)"
            else " • " + parts.joinToString(" + ")
        } else if (audioDenied) {
            audioTxt = " • No audio (permission denied)"
        }

        r.start()

        active = true
        paused = false
        accumulated = 0L
        segmentStart = SystemClock.elapsedRealtime()
        RecorderState.countdown = 0
        RecorderState.recording = true
        RecorderState.paused = false
        RecorderState.elapsedSec = 0L
        RecorderState.message = null
        RecorderState.info = "${w}x$h • $fps FPS$audioTxt"
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        handler.post(ticker)
    }

    private fun pause() {
        try {
            recorder?.pause()
            pipeline?.paused = true
            accumulated = currentMs()
            paused = true
            RecorderState.paused = true
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        } catch (e: Exception) {
        }
    }

    private fun resume() {
        try {
            recorder?.resume()
            pipeline?.paused = false
            segmentStart = SystemClock.elapsedRealtime()
            paused = false
            RecorderState.paused = false
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        } catch (e: Exception) {
        }
    }

    private fun release() {
        try { recorder?.release() } catch (e: Exception) { }
        try { vdisplay?.release() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        try { pfd?.close() } catch (e: Exception) { }
        recorder = null
        vdisplay = null
        projection = null
        pfd = null
    }

    private fun report(u: Uri?, dur: Long, note: String) {
        if (u != null) {
            val (name, size) = MediaStoreManager.info(this, u)
            RecorderState.lastSaved = u
            RecorderState.libraryVersion++
            RecorderState.message = "Saved: $name\n" +
                    "Duration ${fmtTime(dur / 1000)} • ${Formatter.formatShortFileSize(this, size)}\n" +
                    "Folder: Movies/H4ERA Screen Recorder" + note
        } else {
            RecorderState.lastSaved = null
            RecorderState.message = "Recording was too short or failed. Nothing was saved."
        }
    }

    private fun finish() {
        if (!active) return
        active = false
        handler.removeCallbacks(ticker)
        val dur = currentMs()
        var ok = true
        try {
            recorder?.stop()
        } catch (e: Exception) {
            ok = false // too short / no valid data
        }
        val pl = pipeline
        pipeline = null
        var audioOk = false
        try {
            audioOk = pl?.stop() ?: false
        } catch (e: Exception) {
        }
        val audioWanted = pl != null
        release() // closes the file descriptor before publishing
        val u = uri
        uri = null
        val vf = tmpVideo
        val af = tmpAudio
        tmpVideo = null
        tmpAudio = null

        RecorderState.recording = false
        RecorderState.paused = false
        RecorderState.muted = false
        RecorderState.micActive = false
        RecorderState.countdown = 0

        if (vf != null) {
            // Audio mode: build the final MP4 in the background.
            RecorderState.saving = true
            RecorderState.lastSaved = null
            RecorderState.message = "Finishing video..."
            getSystemService(NotificationManager::class.java).notify(1, buildNotification())
            val okFinal = ok
            val withAudio = audioOk
            Thread {
                var result: Uri? = null
                var note = ""
                if (okFinal) {
                    val nu = MediaStoreManager.createPending(this)
                    if (nu != null) {
                        var merged = false
                        try {
                            val p2 = contentResolver.openFileDescriptor(nu, "rw")
                            if (p2 != null) {
                                merged = Mux.merge(vf, if (withAudio) af else null, p2.fileDescriptor)
                                p2.close()
                            }
                        } catch (e: Exception) {
                            merged = false
                        }
                        if (!merged) {
                            merged = Mux.copyRaw(this, vf, nu)
                            note = "\nAudio could not be added (video only)."
                        } else if (audioWanted && !withAudio) {
                            note = "\nNo audio was captured (source unavailable or blocked by the app)."
                        }
                        if (merged) {
                            MediaStoreManager.publish(this, nu)
                            result = nu
                        } else {
                            MediaStoreManager.discard(this, nu)
                        }
                    }
                }
                vf.delete()
                af?.delete()
                val res = result
                val n = note
                handler.post {
                    report(res, dur, n)
                    RecorderState.saving = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }.start()
        } else {
            if (ok && u != null) {
                MediaStoreManager.publish(this, u)
                report(u, dur, "")
            } else {
                if (u != null) MediaStoreManager.discard(this, u)
                report(null, dur, "")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fail(msg: String) {
        active = false
        counting = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(cdTick)
        handler.removeCallbacks(startRunnable)
        hideCd()
        try { pipeline?.stop() } catch (e: Exception) { }
        pipeline = null
        release()
        uri?.let { MediaStoreManager.discard(this, it) }
        uri = null
        tmpVideo?.delete()
        tmpAudio?.delete()
        tmpVideo = null
        tmpAudio = null
        RecorderState.recording = false
        RecorderState.paused = false
        RecorderState.countdown = 0
        RecorderState.micActive = false
        RecorderState.muted = false
        RecorderState.messa
