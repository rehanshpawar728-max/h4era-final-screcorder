package com.h4era.screenrecorder

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object RecorderState {
    var recording by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var elapsedSec by mutableStateOf(0L)
    var info by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var lastSaved by mutableStateOf<Uri?>(null)
    var libraryVersion by mutableStateOf(0)

    // >0 = counting down, -1 = starting, 0 = idle
    var countdown by mutableStateOf(0)
    var floatingOn by mutableStateOf(false)
    var muted by mutableStateOf(false)
    var micActive by mutableStateOf(false)
    var saving by mutableStateOf(false)
}

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("h4era", Context.MODE_PRIVATE)

    fun height(c: Context): Int = sp(c).getInt("height", 720)
    fun fps(c: Context): Int = sp(c).getInt("fps", 30)
    fun mbps(c: Context): Int = sp(c).getInt("mbps", 4)

    // 0 none, 1 mic, 2 internal, 3 internal + mic
    fun audioMode(c: Context): Int {
        val s = sp(c)
        return s.getInt("audioMode", if (s.getBoolean("mic", false)) 1 else 0)
    }

    fun countdown(c: Context): Boolean = sp(c).getBoolean("countdown", true)
    fun floating(c: Context): Boolean = sp(c).getBoolean("floating", false)
    fun bubbleRec(c: Context): Boolean = sp(c).getBoolean("bubbleRec", false)
    fun dark(c: Context): Boolean = sp(c).getBoolean("dark", true)
    fun byDate(c: Context): Boolean = sp(c).getBoolean("byDate", false)

    fun setFlag(c: Context, key: String, v: Boolean) {
        sp(c).edit().putBoolean(key, v).apply()
    }

    fun setInt(c: Context, key: String, v: Int) {
        sp(c).edit().putInt(key, v).apply()
    }

    fun save(c: Context, height: Int, fps: Int, mbps: Int) {
        sp(c).edit()
            .putInt("height", height)
            .putInt("fps", fps)
            .putInt("mbps", mbps)
            .apply()
    }
}
