package com.h4era.screenrecorder

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.VideoView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

private fun fmtMs(ms: Long): String {
    val s = ms / 1000
    val tenth = (ms % 1000) / 100
    return String.format("%d:%02d.%d", s / 60, s % 60, tenth)
}

@Composable
fun TrimScreen(rec: Rec, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val total = if (rec.durationMs > 0) rec.durationMs else 1000L
    val gap = minOf(500f, total / 2f)

    var start by remember { mutableStateOf(0f) }
    var end by remember { mutableStateOf(total.toFloat()) }
    var playing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var savedUri by remember { mutableStateOf<Uri?>(null) }
    val holder = remember { arrayOfNulls<VideoView>(1) }

    LaunchedEffect(playing) {
        while (playing) {
            delay(100)
            val v = holder[0]
            if (v != null && v.currentPosition >= end.toInt()) {
                v.pause()
                playing = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { holder[0]?.stopPlayback() } catch (e: Exception) { }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("Trim video", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.text)
        }
        Text(
            rec.name,
            color = Pal.muted,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(8.dp))

        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoURI(rec.uri)
                    setOnPreparedListener { seekTo(1) }
                    setOnCompletionListener { playing = false }
                    holder[0] = this
                }
            },
            modifier = Modifier.fillMaxWidth().height(260.dp)
        )

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                val v = holder[0]
                if (v != null) {
                    if (playing) {
                        v.pause()
                        playing = false
                    } else {
                        v.seekTo(start.toInt())
                        v.start()
                        playing = true
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (playing) "Pause preview" else "Preview selected part") }

        Spacer(Modifier.height(12.dp))
        Text("Start: " + fmtMs(start.toLong()), color = Pal.accent)
        Slider(
            value = start,
            onValueChange = {
                start = it.coerceAtMost(maxOf(0f, end - gap))
                holder[0]?.seekTo(start.toInt())
            },
            valueRange = 0f..total.toFloat()
        )
        Text("End: " + fmtMs(end.toLong()), color = Pal.accent)
        Slider(
            value = end,
            onValueChange = {
                end = it.coerceAtLeast(minOf(total.toFloat(), start + gap))
                holder[0]?.seekTo(end.toInt())
            },
            valueRange = 0f..total.toFloat()
        )
        Text(
            "New length: " + fmtMs((end - start).toLong()) + "  (original " + fmtMs(total) + ")",
            color = Pal.text
        )
        Text(
            "Trimming does not re-encode, so quality is unchanged. The cut can begin up to a second or two before your start point (nearest keyframe). Your original video is kept.",
            color = Pal.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(12.dp))
        if (saving) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Saving trimmed copy...", color = Pal.muted, fontSize = 12.sp)
        } else {
            Button(
                onClick = {
                    holder[0]?.pause()
                    playing = false
                    saving = true
                    msg = null
                    savedUri = null
                    val s = start.toLong()
                    val e = end.toLong()
                    val app = ctx.applicationContext
                    Thread {
                        val u = VideoTools.trim(app, rec.uri, rec.name, s, e)
                        Handler(Looper.getMainLooper()).post {
                            saving = false
                            if (u != null) {
                                savedUri = u
                                msg = "Saved a trimmed copy in Movies/H4ERA Screen Recorder. The original was kept."
                                RecorderState.libraryVersion++
                            } else {
                                msg = "Trim failed. Nothing was saved."
                            }
                        }
                    }.start()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save trimmed copy") }
        }

        val m = msg
        if (m != null) {
            Spacer(Modifier.height(12.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Pal.note)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(m, color = Pal.text, fontSize = 14.sp)
                    val su = savedUri
                    if (su != null) {
                        Row {
                            TextButton(onClick = { playVideo(ctx, su) }) { Text("Play") }
                            TextButton(onClick = { shareVideo(ctx, su) }) { Text("Share") }
                            TextButton(onClick = onBack) { Text("Done") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
