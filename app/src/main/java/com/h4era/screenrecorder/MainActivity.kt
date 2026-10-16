package com.h4era.screenrecorder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush

class MainActivity : ComponentActivity() {

    private val projLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, RecordingService::class.java)
            i.putExtra("code", r.resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            moveTaskToBack(true)
        } else {
            RecorderState.message = "Screen capture permission was denied."
        }
    }

    private val shotLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        val data = r.data
        if (r.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenshotService::class.java)
            i.putExtra("code", r.resultCode)
            i.putExtra("data", data)
            startForegroundService(i)
            Toast.makeText(
                this,
                "Screenshot in 3 seconds - switch to the screen you want",
                Toast.LENGTH_LONG
            ).show()
            moveTaskToBack(true)
        } else {
            RecorderState.message = "Screenshot permission was denied."
        }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        askProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pal.dark = Prefs.dark(this)
        RecorderState.floatingOn = Prefs.floating(this)
        setContent {
            H4Theme {
                App(
                    onToggle = { toggleRecord() },
                    onPause = { sendAction("TOGGLE") },
                    onMute = { sendAction("MUTE") },
                    onShot = { takeShot() }
                )
            }
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.floating(this)) {
            if (Settings.canDrawOverlays(this)) {
                startService(Intent(this, FloatingService::class.java))
            } else {
                Prefs.setFlag(this, "floating", false)
                RecorderState.floatingOn = false
            }
        }
    }

    private fun handleLaunch(i: Intent?) {
        if (i != null && i.getBooleanExtra("start_record", false)) {
            i.removeExtra("start_record")
            if (!RecorderState.recording && RecorderState.countdown == 0) toggleRecord()
        }
    }

    private fun sendAction(a: String) {
        startService(Intent(this, RecordingService::class.java).setAction(a))
    }

    private fun takeShot() {
        if (RecorderState.recording || RecorderState.countdown != 0 || RecorderState.saving) return
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        shotLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun toggleRecord() {
        if (RecorderState.saving) {
            Toast.makeText(this, "Still saving the last video...", Toast.LENGTH_SHORT).show()
            return
        }
        if (RecorderState.recording || RecorderState.countdown != 0) {
            sendAction("STOP")
            return
        }
        RecorderState.message = null
        val needed = ArrayList<String>()
        if (Prefs.audioMode(this) != 0 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isEmpty()) askProjection() else permLauncher.launch(needed.toTypedArray())
    }

    private fun askProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projLauncher.launch(mpm.createScreenCaptureIntent())
    }
}

@Composable
fun App(onToggle: () -> Unit, onPause: () -> Unit, onMute: () -> Unit, onShot: () -> Unit) {
    var screen by remember { mutableStateOf("home") }
    BackHandler(enabled = screen != "home") { screen = "home" }

    Surface(modifier = Modifier.fillMaxSize(), color = Pal.bg) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Pal.bg2, Pal.bg)))
        ) {
            when (screen) {
                "library" -> LibraryScreen { screen = "home" }
                "settings" -> SettingsScreen { screen = "home" }
                else -> HomeScreen(
                    onToggle = onToggle,
                    onPause = onPause,
                    onMute = onMute,
                    onShot = onShot,
                    onLibrary = { screen = "library" },
                    onSettings = { screen = "settings" }
                )
            }
        }
    }
}
