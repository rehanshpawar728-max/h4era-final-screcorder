package com.h4era.screenrecorder

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Rec(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val size: Long,
    val dateSec: Long,
    val width: Int,
    val height: Int
)

class RenameResult(val ok: Boolean, val sender: IntentSender?)

object MediaStoreManager {
    private const val FOLDER = "Movies/H4ERA Screen Recorder/"

    // Creates a hidden (IS_PENDING) entry in the public Movies collection.
    fun createPending(c: Context): Uri? {
        val now = Date()
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(now)
        val folder = if (Prefs.byDate(c)) {
            FOLDER + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now) + "/"
        } else {
            FOLDER
        }
        val v = ContentValues()
        v.put(MediaStore.Video.Media.DISPLAY_NAME, "H4ERA_Recording_$stamp.mp4")
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        v.put(MediaStore.Video.Media.RELATIVE_PATH, folder)
        v.put(MediaStore.Video.Media.IS_PENDING, 1)
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return c.contentResolver.insert(collection, v)
    }

    // Makes the finished video visible to Gallery apps.
    fun publish(c: Context, uri: Uri) {
        val v = ContentValues()
        v.put(MediaStore.Video.Media.IS_PENDING, 0)
        c.contentResolver.update(uri, v, null, null)
    }

    // Removes an unfinished/corrupt entry.
    fun discard(c: Context, uri: Uri) {
        try {
            c.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
        }
    }

    fun info(c: Context, uri: Uri): Pair<String, Long> {
        c.contentResolver.query(
            uri,
            arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE),
            null, null, null
        )?.use {
            if (it.moveToFirst()) {
                return Pair(it.getString(0) ?: "video.mp4", it.getLong(1))
            }
        }
        return Pair("video.mp4", 0L)
    }

    fun query(c: Context): List<Rec> {
        val out = ArrayList<Rec>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        val sel = MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?"
        val args = arrayOf("Movies/H4ERA Screen Recorder%")
        val base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        c.contentResolver.query(base, proj, sel, args, MediaStore.Video.Media.DATE_ADDED + " DESC")?.use { cur ->
            while (cur.moveToNext()) {
                val id = cur.getLong(0)
                out.add(
                    Rec(
                        id,
                        ContentUris.withAppendedId(base, id),
                        cur.getString(1) ?: "video.mp4",
                        cur.getLong(2),
                        cur.getLong(3),
                        cur.getLong(4),
                        cur.getInt(5),
                        cur.getInt(6)
                    )
                )
            }
        }
        return out
    }

    // Returns null if deleted (or impossible). Returns an IntentSender if Android needs user approval.
    fun delete(c: Context, uri: Uri): IntentSender? {
        return try {
            c.contentResolver.delete(uri, null, null)
            null
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30) {
                MediaStore.createDeleteRequest(c.contentResolver, listOf(uri)).intentSender
            } else if (e is RecoverableSecurityException) {
                e.userAction.actionIntent.intentSender
            } else {
                null
            }
        }
    }

    // Renames a video. If Android needs approval, returns a sender to ask the user.
    fun rename(c: Context, uri: Uri, newName: String): RenameResult {
        var n = newName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        if (n.isEmpty()) return RenameResult(false, null)
        if (!n.endsWith(".mp4", true)) n += ".mp4"
        val v = ContentValues()
        v.put(MediaStore.Video.Media.DISPLAY_NAME, n)
        return try {
            c.contentResolver.update(uri, v, null, null)
            RenameResult(true, null)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 30) {
                RenameResult(false, MediaStore.createWriteRequest(c.contentResolver, listOf(uri)).intentSender)
            } else if (e is RecoverableSecurityException) {
                RenameResult(false, e.userAction.actionIntent.intentSender)
            } else {
                RenameResult(false, null)
            }
        } catch (e: Exception) {
            RenameResult(false, null)
        }
    }
}
