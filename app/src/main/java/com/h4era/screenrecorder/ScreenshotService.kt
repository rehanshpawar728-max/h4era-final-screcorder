package com.h4era.screenrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.Display
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenshotService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var vd: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var tries = 0
    private var busy = false
    private var sw = 0
    private var sh = 0
    private var sdpi = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("shot", "Screenshot", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "shot")
            .setContentTitle("H4ERA - screenshot")
            .setContentText("Capturing the screen...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
        startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (busy) return START_NOT_STICKY
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data")
        if (data == null) {
            done("Screenshot failed: no permission data")
            return START_NOT_STICKY
        }
        busy = true
        try {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(code, data)
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                }
            }, handler)

            val dm = DisplayMetrics()
            val dmgr = getSystemService(DISPLAY_SERVICE) as DisplayManager
            dmgr.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(dm)
            sw = dm.widthPixels
            sh = dm.heightPixels
            sdpi = dm.densityDpi

            // wait so the user can switch to the screen they want
            handler.postDelayed({ startCapture() }, 3500)
        } catch (e: Exception) {
            done("Screenshot failed: " + e.message)
        }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        try {
            val p = projection ?: return
            val r = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 2)
            reader = r
            vd = p.createVirtualDisplay(
                "H4ERA-shot", sw, sh, sdpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                r.surface, null, handler
            )
            handler.postDelayed({ poll() }, 400)
        } catch (e: Exception) {
            done("Screenshot failed: " + e.message)
        }
    }

    private fun poll() {
        val r = reader ?: return
        var img: Image? = null
        try {
            img = r.acquireLatestImage()
        } catch (e: Exception) {
        }
        if (img == null) {
            tries++
            if (tries > 16) done("Screenshot failed: no frame received")
            else handler.postDelayed({ poll() }, 250)
            return
        }
        var bmp: Bitmap? = null
        try {
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val bw = rowStride / pixelStride
            val full = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            bmp = if (bw != img.width) Bitmap.createBitmap(full, 0, 0, img.width, img.height) else full
        } catch (e: Exception) {
            bmp = null
        } finally {
            img.close()
        }
        val b = bmp
        if (b == null) {
            done("Screenshot failed: could not read the frame")
            return
        }
        Thread {
            val msg = save(b)
            handler.post { done(msg) }
        }.start()
    }

    private fun save(bmp: Bitmap): String {
        return try {
            val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val v = ContentValues()
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "H4ERA_Screenshot_$stamp.png")
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/H4ERA Screen Recorder/")
            v.put(MediaStore.Images.Media.IS_PENDING, 1)
            val uri = contentResolver.insert(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v
            ) ?: return "Screenshot failed: cannot create file"
            val os = contentResolver.openOutputStream(uri)
            if (os == null) {
                contentResolver.delete(uri, null, null)
                return "Screenshot failed: cannot write file"
            }
            os.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val v2 = ContentValues()
            v2.put(MediaStore.Images.Media.IS_PENDING, 0)
            contentResolver.update(uri, v2, null, null)
            "Screenshot saved to Pictures/H4ERA Screen Recorder"
        } catch (e: Exception) {
            "Screenshot failed: " + e.message
        }
    }

    private fun release() {
        try { vd?.release() } catch (e: Exception) { }
        try { reader?.close() } catch (e: Exception) { }
        try { projection?.stop() } catch (e: Exception) { }
        vd = null
        reader = null
        projection = null
    }

    private fun done(msg: String) {
        release()
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        release()
        super.onDestroy()
    }
}
