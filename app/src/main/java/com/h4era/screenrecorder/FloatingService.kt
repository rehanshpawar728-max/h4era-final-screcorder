package com.h4era.screenrecorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

fun setFloating(c: Context, on: Boolean) {
    if (on) {
        if (Settings.canDrawOverlays(c)) {
            Prefs.setFlag(c, "floating", true)
            RecorderState.floatingOn = true
            c.startService(Intent(c, FloatingService::class.java))
        } else {
            Toast.makeText(
                c,
                "Allow \"Display over other apps\" for H4ERA, then come back and turn it on again.",
                Toast.LENGTH_LONG
            ).show()
            val i = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + c.packageName)
            )
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
        }
    } else {
        Prefs.setFlag(c, "floating", false)
        RecorderState.floatingOn = false
        c.stopService(Intent(c, FloatingService::class.java))
    }
}

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private var main: TextView? = null
    private var pauseBtn: TextView? = null
    private var closeBtn: TextView? = null
    private val handler = Handler(Looper.getMainLooper())

    private val updater = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (root == null) {
            if (!Settings.canDrawOverlays(this)) {
                stopSelf()
                return START_NOT_STICKY
            }
            build()
            handler.post(updater)
        }
        return START_STICKY
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(color)
        t.textSize = 14f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(14), dp(10), dp(14), dp(10))
        return t
    }

    private fun build() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#E6111A2E"))
        bg.cornerRadius = dp(24).toFloat()
        bg.setStroke(dp(1), Color.parseColor("#00E5FF"))
        r.background = bg

        val m = label("REC", Color.parseColor("#00E5FF"))
        val p = label("Pause", Color.WHITE)
        val x = label("X", Color.parseColor("#8A94A6"))
        p.visibility = View.GONE
        r.addView(m)
        r.addView(p)
        r.addView(x)

        p.setOnClickListener {
            startService(Intent(this, RecordingService::class.java).setAction("TOGGLE"))
        }
        x.setOnClickListener { setFloating(this, false) }

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(12)
        lp.y = dp(160)

        m.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0
            var sy = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = lp.x
                        sy = lp.y
                        tx = e.rawX
                        ty = e.rawY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - tx).toInt()
                        val dy = (e.rawY - ty).toInt()
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            lp.x = sx + dx
                            lp.y = sy + dy
                            wm.updateViewLayout(r, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) onMainTap()
                    }
                }
                return true
            }
        })

        wm.addView(r, lp)
        root = r
        main = m
        pauseBtn = p
        closeBtn = x
    }

    private fun onMainTap() {
        if (RecorderState.recording || RecorderState.countdown != 0) {
            startService(Intent(this, RecordingService::class.java).setAction("STOP"))
        } else {
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("start_record", true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }
    }

    private fun refresh() {
        val r = root ?: return
        val m = main ?: return
        val p = pauseBtn ?: return
        val x = closeBtn ?: return
        val rec = RecorderState.recording
        val cd = RecorderState.countdown
        val busy = rec || cd != 0

        if (busy && !Prefs.bubbleRec(this)) {
            r.visibility = View.GONE
            return
        }
        r.visibility = View.VISIBLE

        if (rec) {
            m.text = "STOP " + fmtTime(RecorderState.elapsedSec)
            m.setTextColor(Color.parseColor("#FF5252"))
            p.text = if (RecorderState.paused) "Resume" else "Pause"
            p.visibility = View.VISIBLE
            x.visibility = View.GONE
        } else if (cd != 0) {
            m.text = "CANCEL"
            m.setTextColor(Color.parseColor("#FFC107"))
            p.visibility = View.GONE
            x.visibility = View.GONE
        } else {
            m.text = "REC"
            m.setTextColor(Color.parseColor("#00E5FF"))
            p.visibility = View.GONE
            x.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val r = root
        if (r != null) {
            try { wm.removeView(r) } catch (e: Exception) { }
        }
        root = null
        super.onDestroy()
    }
}
