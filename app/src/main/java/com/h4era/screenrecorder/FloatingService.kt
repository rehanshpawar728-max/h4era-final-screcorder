package com.h4era.screenrecorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

// Transparent full-screen canvas used for drawing on the screen.
class DrawView(c: Context) : View(c) {

    private class Stroke(val path: Path, val paint: Paint)

    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null
    var penColor: Int = Color.parseColor("#00E5FF")

    private fun newPaint(): Paint {
        val p = Paint()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = 5f * resources.displayMetrics.density
        p.color = penColor
        return p
    }

    override fun onDraw(canvas: Canvas) {
        for (s in strokes) {
            canvas.drawPath(s.path, s.paint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                val p = Path()
                p.moveTo(e.x, e.y)
                p.lineTo(e.x + 0.1f, e.y)
                val s = Stroke(p, newPaint())
                strokes.add(s)
                current = s
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                current?.path?.lineTo(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                current = null
            }
        }
        return true
    }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.size - 1)
            invalidate()
        }
    }

    fun clearAll() {
        strokes.clear()
        invalidate()
    }
}

class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var normalGroup: LinearLayout? = null
    private var drawGroup: LinearLayout? = null
    private var recBtn: TextView? = null
    private var pauseBtn: TextView? = null
    private var closeBtn: TextView? = null
    private var colorBtn: TextView? = null
    private var drawView: DrawView? = null
    private var drawLp: WindowManager.LayoutParams? = null
    private var expanded = false
    private var drawing = false
    private var colorIndex = 0

    private val cCyan = Color.parseColor("#00E5FF")
    private val cRed = Color.parseColor("#FF5252")
    private val cAmber = Color.parseColor("#FFC107")
    private val cMuted = Color.parseColor("#B8C4D9")
    private val penColors = intArrayOf(
        Color.parseColor("#00E5FF"),
        Color.WHITE,
        Color.parseColor("#FF5252"),
        Color.parseColor("#FFC107"),
        Color.parseColor("#69F0AE")
    )
    private val drawFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

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

    private fun btn(text: String, color: Int): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(color)
        t.textSize = 12f
        t.typeface = Typeface.DEFAULT_BOLD
        t.gravity = Gravity.CENTER
        t.setPadding(dp(9), dp(12), dp(9), dp(12))
        return t
    }

    private fun build() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        // Drawing layer (added first so the controls sit on top of it)
        val dv = DrawView(this)
        dv.penColor = penColors[colorIndex]
        val dl = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            drawFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )
        dl.alpha = 0.75f
        wm.addView(dv, dl)
        drawView = dv
        drawLp = dl

        // Controls
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL

        val b = TextView(this)
        b.gravity = Gravity.CENTER
        b.typeface = Typeface.DEFAULT_BOLD
        val bb = GradientDrawable()
        bb.shape = GradientDrawable.OVAL
        bb.setColor(Color.parseColor("#F2111A2E"))
        bb.setStroke(dp(2), cCyan)
        b.background = bb
        r.addView(b, LinearLayout.LayoutParams(dp(54), dp(54)))

        val pn = LinearLayout(this)
        pn.orientation = LinearLayout.HORIZONTAL
        pn.gravity = Gravity.CENTER_VERTICAL
        val pb = GradientDrawable()
        pb.setColor(Color.parseColor("#F2111A2E"))
        pb.cornerRadius = dp(27).toFloat()
        pb.setStroke(dp(1), Color.parseColor("#2979FF"))
        pn.background = pb
        pn.setPadding(dp(4), 0, dp(4), 0)
        pn.visibility = View.GONE
        val pl = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        pl.leftMargin = dp(6)
        r.addView(pn, pl)

        val n = LinearLayout(this)
        n.orientation = LinearLayout.HORIZONTAL
        n.gravity = Gravity.CENTER_VERTICAL
        val rec = btn("REC", cCyan)
        val pause = btn("Pause", Color.WHITE)
        val drawB = btn("Draw", Color.WHITE)
        val fade = btn("Fade", cMuted)
        val close = btn("X", cMuted)
        pause.visibility = View.GONE
        n.addView(rec)
        n.addView(pause)
        n.addView(drawB)
        n.addView(fade)
        n.addView(close)

        val d = LinearLayout(this)
        d.orientation = LinearLayout.HORIZONTAL
        d.gravity = Gravity.CENTER_VERTICAL
        val colorB = btn("Color", penColors[colorIndex])
        val undoB = btn("Undo", Color.WHITE)
        val clearB = btn("Clear", Color.WHITE)
        val doneB = btn("Done", cCyan)
        d.addView(colorB)
        d.addView(undoB)
        d.addView(clearB)
        d.addView(doneB)
        d.visibility = View.GONE

        pn.addView(n)
        pn.addView(d)

        rec.setOnClickListener { onRecTap() }
        pause.setOnClickListener {
            startService(Intent(this, RecordingService::class.java).setAction("TOGGLE"))
        }
        drawB.setOnClickListener { enterDraw() }
        fade.setOnClickListener { cycleFade() }
        close.setOnClickListener { setFloating(this, false) }
        colorB.setOnClickListener { cycleColor() }
        undoB.setOnClickListener { drawView?.undo() }
        clearB.setOnClickListener { drawView?.clearAll() }
        doneB.setOnClickListener { exitDraw() }

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

        b.setOnTouchListener(object : View.OnTouchListener {
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
                            val sw = resources.displayMetrics.widthPixels
                            val sh = resources.displayMetrics.heightPixels
                            lp.x = (sx + dx).coerceIn(0, maxOf(0, sw - r.width))
                            lp.y = (sy + dy).coerceIn(0, maxOf(0, sh - r.height))
                            wm.updateViewLayout(r, lp)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved && !drawing) setExpanded(!expanded)
                    }
                }
                return true
            }
        })

        wm.addView(r, lp)
        root = r
        bubble = b
        panel = pn
        normalGroup = n
        drawGroup = d
        recBtn = rec
        pauseBtn = pause
        closeBtn = close
        colorBtn = colorB
        applyAlpha()
    }

    private fun applyAlpha() {
        val r = root ?: return
        val level = getSharedPreferences("h4era", MODE_PRIVATE).getInt("bubbleAlpha", 100)
        r.alpha = if (expanded || drawing) 1f else level / 100f
    }

    private fun cycleFade() {
        val cur = getSharedPreferences("h4era", MODE_PRIVATE).getInt("bubbleAlpha", 100)
        val next = if (cur >= 100) 70 else if (cur >= 70) 40 else 100
        Prefs.setInt(this, "bubbleAlpha", next)
        Toast.makeText(
            this,
            "Opacity $next% (applies when the bubble is collapsed)",
            Toast.LENGTH_SHORT
        ).show()
        applyAlpha()
    }

    private fun clampToScreen() {
        val r = root ?: return
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        lp.x = lp.x.coerceIn(0, maxOf(0, sw - r.width))
        lp.y = lp.y.coerceIn(0, maxOf(0, sh - r.height))
        try {
            wm.updateViewLayout(r, lp)
        } catch (e: Exception) {
        }
    }

    private fun setExpanded(v: Boolean) {
        val p = panel ?: return
        expanded = v
        p.animate().cancel()
        if (v) {
            p.visibility = View.VISIBLE
            p.pivotX = 0f
            p.alpha = 0f
            p.scaleX = 0.6f
            p.animate().alpha(1f).scaleX(1f).setDuration(160).start()
            handler.postDelayed({ clampToScreen() }, 120)
        } else {
            p.animate().alpha(0f).scaleX(0.6f).setDuration(120).withEndAction {
                if (!expanded) {
                    p.visibility = View.GONE
                }
            }.start()
        }
        applyAlpha()
    }

    private fun setDrawTouchable(t: Boolean) {
        val dv = drawView ?: return
        val l = drawLp ?: return
        l.flags = if (t) drawFlags else (drawFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        try {
            wm.updateViewLayout(dv, l)
        } catch (e: Exception) {
        }
    }

    private fun enterDraw() {
        drawing = true
        setDrawTouchable(true)
        normalGroup?.visibility = View.GONE
        drawGroup?.visibility = View.VISIBLE
        applyAlpha()
    }

    // Ink stays on screen (and in the recording) after Done. Use Clear to remove it.
    private fun exitDraw() {
        drawing = false
        setDrawTouchable(false)
        drawGroup?.visibility = View.GONE
        normalGroup?.visibility = View.VISIBLE
        applyAlpha()
    }

    private fun cycleColor() {
        colorIndex = (colorIndex + 1) % penColors.size
        drawView?.penColor = penColors[colorIndex]
        colorBtn?.setTextColor(penColors[colorIndex])
    }

    private fun onRecTap() {
        if (RecorderState.recording || RecorderState.countdown != 0) {
            startService(Intent(this, RecordingService::class.java).setAction("STOP"))
        } else {
            if (drawing) exitDraw()
            setExpanded(false)
            val i = Intent(this, MainActivity::class.java)
            i.putExtra("start_record", true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            startActivity(i)
        }
    }

    private fun refresh() {
        val r = root ?: return
        val b = bubble ?: return
        val rb = recBtn ?: return
        val pb = pauseBtn ?: return
        val cb = closeBtn ?: return
        val rec = RecorderState.recording
        val cd = RecorderState.countdown
        val busy = rec || cd != 0

        if (busy && !Prefs.bubbleRec(this)) {
            if (drawing) exitDraw()
            if (expanded) setExpanded(false)
            r.visibility = View.GONE
            return
        }
        r.visibility = View.VISIBLE

        if (rec) {
            b.text = fmtTime(RecorderState.elapsedSec)
            b.textSize = 11f
            b.setTextColor(if (RecorderState.paused) cAmber else cRed)
        } else if (cd > 0) {
            b.text = cd.toString()
            b.textSize = 20f
            b.setTextColor(cAmber)
        } else if (cd < 0) {
            b.text = ".."
            b.textSize = 20f
            b.setTextColor(cAmber)
        } else {
            b.text = "H4"
            b.textSize = 16f
            b.setTextColor(cCyan)
        }

        if (rec) {
            rb.text = "Stop"
            rb.setTextColor(cRed)
        } else if (cd != 0) {
            rb.text = "Cancel"
            rb.setTextColor(cAmber)
        } else if (RecorderState.saving) {
            rb.text = "Wait"
            rb.setTextColor(cAmber)
        } else {
            rb.text = "REC"
            rb.setTextColor(cCyan)
        }

        pb.visibility = if (rec) View.VISIBLE else View.GONE
        pb.text = if (RecorderState.paused) "Resume" else "Pause"
        cb.visibility = if (busy) View.GONE else View.VISIBLE
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        root?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        drawView?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        root = null
        drawView = null
        super.onDestroy()
    }
}
