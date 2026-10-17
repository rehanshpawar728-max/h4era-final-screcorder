package com.h4era.screenrecorder

import android.app.Activity
import android.net.Uri
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var items by remember { mutableStateOf(listOf<Rec>()) }
    var sort by remember { mutableStateOf(0) }
    var refresh by remember { mutableStateOf(0) }
    var toDelete by remember { mutableStateOf<Rec?>(null) }
    var toRename by remember { mutableStateOf<Rec?>(null) }
    var toCompress by remember { mutableStateOf<Rec?>(null) }
    var trimRec by remember { mutableStateOf<Rec?>(null) }
    var newName by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<Uri, String>?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { res ->
        val pr = pending
        pending = null
        if (pr != null && res.resultCode == Activity.RESULT_OK) {
            MediaStoreManager.rename(ctx, pr.first, pr.second)
        }
        refresh++
        RecorderState.libraryVersion++
    }

    LaunchedEffect(refresh, RecorderState.libraryVersion) {
        items = withContext(Dispatchers.IO) { MediaStoreManager.query(ctx) }
    }

    val sorted = when (sort) {
        0 -> items.sortedByDescending { it.dateSec }
        1 -> items.sortedBy { it.dateSec }
        else -> items.sortedByDescending { it.size }
    }

    val tr = trimRec
    if (tr != null) {
        BackHandler { trimRec = null }
        TrimScreen(tr) { trimRec = null }
    } else {

        val d = toDelete
        if (d != null) {
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text("Delete recording?") },
                text = { Text(d.name) },
                confirmButton = {
                    TextButton(onClick = {
                        toDelete = null
                        val sender = MediaStoreManager.delete(ctx, d.uri)
                        if (sender != null) {
                            launcher.launch(IntentSenderRequest.Builder(sender).build())
                        } else {
                            refresh++
                            RecorderState.libraryVersion++
                        }
                    }) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
            )
        }

        val rn = toRename
        if (rn != null) {
            AlertDialog(
                onDismissRequest = { toRename = null },
                title = { Text("Rename recording") },
                text = {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        toRename = null
                        val res = MediaStoreManager.rename(ctx, rn.uri, newName)
                        val s = res.sender
                        if (res.ok) {
                            refresh++
                            RecorderState.libraryVersion++
                        } else if (s != null) {
                            pending = Pair(rn.uri, newName)
                            launcher.launch(IntentSenderRequest.Builder(s).build())
                        } else {
                            Toast.makeText(ctx, "Could not rename", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { toRename = null }) { Text("Cancel") } }
            )
        }

        val cp = toCompress
        if (cp != null) {
            AlertDialog(
                onDismissRequest = { toCompress = null },
                title = { Text("Compress video") },
                text = {
                    Column {
                        Text(
                            cp.name + "\n\nThis re-encodes a smaller copy (a little quality is lost). Your original is kept. Keep the app open while it works.",
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Chip("Smaller - 2 Mbps", false, Modifier.fillMaxWidth()) {
                            toCompress = null
                            VideoTools.compress(ctx, cp.uri, cp.name, 2_000_000)
                        }
                        Spacer(Modifier.height(8.dp))
                        Chip("Smallest - 1 Mbps", false, Modifier.fillMaxWidth()) {
                            toCompress = null
                            VideoTools.compress(ctx, cp.uri, cp.name, 1_000_000)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { toCompress = null }) { Text("Cancel") } }
            )
        }

        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← Back") }
                Spacer(Modifier.width(8.dp))
                Text("Recordings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pal.text)
            }

            if (ToolState.busy) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Pal.note)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            ToolState.title +
                                    (if (ToolState.progress >= 0) " " + ToolState.progress + "%" else ""),
                            color = Pal.text
                        )
                        Spacer(Modifier.height(6.dp))
                        if (ToolState.progress >= 0) {
                            LinearProgressIndicator(
                                progress = ToolState.progress / 100f,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "Keep the app open until it finishes.",
                            color = Pal.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (ToolState.cancellable) {
                            TextButton(onClick = { VideoTools.cancel() }) { Text("Cancel") }
                        }
                    }
                }
            }

            val tres = ToolState.result
            if (tres != null && !ToolState.busy) {
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Pal.note)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(tres, color = Pal.text, fontSize = 14.sp)
                        Row {
                            val ru = ToolState.resultUri
                            if (ru != null) {
                                TextButton(onClick = { playVideo(ctx, ru) }) { Text("Play") }
                                TextButton(onClick = { shareVideo(ctx, ru) }) { Text("Share") }
                            }
                            TextButton(onClick = {
                                ToolState.result = null
                                ToolState.resultUri = null
                            }) { Text("Dismiss") }
                        }
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Newest", sort == 0, Modifier.weight(1f)) { sort = 0 }
                Chip("Oldest", sort == 1, Modifier.weight(1f)) { sort = 1 }
                Chip("Largest", sort == 2, Modifier.weight(1f)) { sort = 2 }
            }
            Spacer(Modifier.height(8.dp))

            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recordings yet.\nTap REC on the home screen to start.", color = Pal.muted)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(sorted, key = { it.id }) { r ->
                        val date = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
                            .format(Date(r.dateSec * 1000))
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            colors = CardDefaults.cardColors(containerColor = Pal.card)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Thumb(r.uri, Modifier.size(width = 112.dp, height = 64.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            r.name,
                                            color = Pal.text,
                                            fontSize = 13.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            fmtTime(r.durationMs / 1000) + " • " + r.width + "x" + r.height,
                                            color = Pal.muted,
                                            fontSize = 12.sp
                                        )
                                        Text(
                                            Formatter.formatShortFileSize(ctx, r.size) + " • " + date,
                                            color = Pal.muted,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                Row {
                                    TextButton(onClick = { playVideo(ctx, r.uri) }) { Text("Play") }
                                    TextButton(onClick = { shareVideo(ctx, r.uri) }) { Text("Share") }
                                    TextButton(onClick = {
                                        newName = r.name.removeSuffix(".mp4")
                                        toRename = r
                                    }) { Text("Rename") }
                                    TextButton(onClick = { toDelete = r }) { Text("Delete") }
                                }
                                Row {
                                    TextButton(onClick = { trimRec = r }) { Text("Trim") }
                                    TextButton(
                                        onClick = { toCompress = r },
                                        enabled = !ToolState.busy && !RecorderState.recording
                                    ) { Text("Compress") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
