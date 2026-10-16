package com.h4era.screenrecorder

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var res by remember { mutableStateOf(Prefs.height(ctx)) }
    var fps by remember { mutableStateOf(Prefs.fps(ctx)) }
    var mbps by remember { mutableStateOf(Prefs.mbps(ctx)) }
    var audio by remember { mutableStateOf(Prefs.audioMode(ctx)) }
    var cdOn by remember { mutableStateOf(Prefs.countdown(ctx)) }
    var showRec by remember { mutableStateOf(Prefs.bubbleRec(ctx)) }
    var byDate by remember { mutableStateOf(Prefs.byDate(ctx)) }

    fun commit() {
        Prefs.save(ctx, res, fps, mbps)
    }

    val rowMod = Modifier.fillMaxWidth()
    val gap = Arrangement.spacedBy(8.dp)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }

        Text("Theme", color = Pal.accent, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("Dark", Pal.dark, Modifier.weight(1f)) {
                Pal.dark = true; Prefs.setFlag(ctx, "dark", true)
            }
            Chip("Light", !Pal.dark, Modifier.weight(1f)) {
                Pal.dark = false; Prefs.setFlag(ctx, "dark", false)
            }
        }

        Text("Quality preset", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("Low", res == 480 && fps == 30 && mbps == 2, Modifier.weight(1f)) {
                res = 480; fps = 30; mbps = 2; commit()
            }
            Chip("Balanced", res == 720 && fps == 30 && mbps == 4, Modifier.weight(1f)) {
                res = 720; fps = 30; mbps = 4; commit()
            }
            Chip("High", res == 1080 && fps == 30 && mbps == 8, Modifier.weight(1f)) {
                res = 1080; fps = 30; mbps = 8; commit()
            }
            Chip("Max", res == 1080 && fps == 60 && mbps == 12, Modifier.weight(1f)) {
                res = 1080; fps = 60; mbps = 12; commit()
            }
        }

        Text("Resolution", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("480p", res == 480, Modifier.weight(1f)) { res = 480; commit() }
            Chip("720p", res == 720, Modifier.weight(1f)) { res = 720; commit() }
            Chip("1080p", res == 1080, Modifier.weight(1f)) { res = 1080; commit() }
            Chip("Native", res == 0, Modifier.weight(1f)) { res = 0; commit() }
        }

        Text("Frame rate", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("24", fps == 24, Modifier.weight(1f)) { fps = 24; commit() }
            Chip("30", fps == 30, Modifier.weight(1f)) { fps = 30; commit() }
            Chip("60", fps == 60, Modifier.weight(1f)) { fps = 60; commit() }
        }
        Text(
            "If your device can't do the chosen size or FPS, the app lowers it automatically.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Video bitrate (Mbps)", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("2", mbps == 2, Modifier.weight(1f)) { mbps = 2; commit() }
            Chip("4", mbps == 4, Modifier.weight(1f)) { mbps = 4; commit() }
            Chip("8", mbps == 8, Modifier.weight(1f)) { mbps = 8; commit() }
            Chip("12", mbps == 12, Modifier.weight(1f)) { mbps = 12; commit() }
        }
        Text(
            "Estimated size: about " + String.format("%.0f", mbps * 60 / 8.0) + " MB per minute",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Audio", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("None", audio == 0, Modifier.weight(1f)) { audio = 0; Prefs.setInt(ctx, "audioMode", 0) }
            Chip("Mic", audio == 1, Modifier.weight(1f)) { audio = 1; Prefs.setInt(ctx, "audioMode", 1) }
            Chip("Internal", audio == 2, Modifier.weight(1f)) { audio = 2; Prefs.setInt(ctx, "audioMode", 2) }
            Chip("Both", audio == 3, Modifier.weight(1f)) { audio = 3; Prefs.setInt(ctx, "audioMode", 3) }
        }
        Text(
            "Internal audio records sound played by your phone (Android 10+). Some apps and games block it, and then that audio is silent. If an audio source is unavailable, the app tells you after recording.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Countdown before recording", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("3-2-1 on", cdOn, Modifier.weight(1f)) {
                cdOn = true; Prefs.setFlag(ctx, "countdown", true)
            }
            Chip("Off", !cdOn, Modifier.weight(1f)) {
                cdOn = false; Prefs.setFlag(ctx, "countdown", false)
            }
        }
        Text(
            "The big countdown number needs the \"Display over other apps\" permission. Without it, the countdown shows in the notification.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Floating button", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("On", RecorderState.floatingOn, Modifier.weight(1f)) { setFloating(ctx, true) }
            Chip("Off", !RecorderState.floatingOn, Modifier.weight(1f)) { setFloating(ctx, false) }
        }
        Text(
            "Drag it anywhere. Tap REC to start, tap STOP to finish. After allowing the permission, come back and tap On again.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Floating button while recording", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("Hide it", !showRec, Modifier.weight(1f)) {
                showRec = false; Prefs.setFlag(ctx, "bubbleRec", false)
            }
            Chip("Show it", showRec, Modifier.weight(1f)) {
                showRec = true; Prefs.setFlag(ctx, "bubbleRec", true)
            }
        }
        Text(
            "If shown, the button will appear in your video. \"Hide it\" keeps videos clean; use the notification to pause, mute or stop.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Organize recordings", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        Row(rowMod, horizontalArrangement = gap) {
            Chip("One folder", !byDate, Modifier.weight(1f)) {
                byDate = false; Prefs.setFlag(ctx, "byDate", false)
            }
            Chip("By date", byDate, Modifier.weight(1f)) {
                byDate = true; Prefs.setFlag(ctx, "byDate", true)
            }
        }
        Text(
            "\"By date\" saves into Movies/H4ERA Screen Recorder/2026-10-16/ style folders.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text("Touch indicators", color = Pal.accent, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
        OutlinedButton(onClick = {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(
                    ctx,
                    "Turn on Developer options first (Settings > About phone > tap Build number 7 times)",
                    Toast.LENGTH_LONG
                ).show()
            }
        }) { Text("Open Developer options (Show taps)") }
        Text(
            "Android only lets the system draw touch dots. Turn on \"Show taps\" there and they will appear in your recordings.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)
        )

        Text(
            "Changes apply to your next recording.",
            color = Pal.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp, bottom = 24.dp)
        )
    }
}
