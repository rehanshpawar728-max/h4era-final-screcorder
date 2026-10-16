package com.h4era.screenrecorder

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun fmtTime(sec: Long): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
}

fun audioLabel(mode: Int): String = when (mode) {
    1 -> "Microphone"
    2 -> "Internal audio"
    3 -> "Internal + Microphone"
    else -> "No audio"
}

fun playVideo(c: Context, uri: Uri) {
    try {
        val i = Intent(Intent.ACTION_VIEW)
        i.setDataAndType(uri, "video/mp4")
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(i)
    } catch (e: Exception) {
        Toast.makeText(c, "No video player found", Toast.LENGTH_SHORT).show()
    }
}

fun shareVideo(c: Context, uri: Uri) {
    try {
        val i = Intent(Intent.ACTION_SEND)
        i.type = "video/mp4"
        i.putExtra(Intent.EXTRA_STREAM, uri)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        c.startActivity(Intent.createChooser(i, "Share recording"))
    } catch (e: Exception) {
        Toast.makeText(c, "Cannot share", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun Thumb(uri: Uri, modifier: Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(
        modifier.clip(RoundedCornerShape(10.dp)).background(Pal.card2),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text("▶", color = Pal.accent)
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Pal.accent else Pal.card2)
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (selected) Pal.onAccent else Pal.text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
