package com.ra.player

import android.content.Context
import android.net.Uri
import java.util.Locale

data class VideoItem(
    val uri: Uri,
    val title: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val folder: String,
    val width: Int,
    val height: Int
)

/** Hands the current playlist from the library screen to the player screen. */
object PlaylistHolder {
    var items: List<VideoItem> = emptyList()
    var index: Int = 0
}

object Fmt {
    fun time(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1024) String.format(Locale.US, "%.1f GB", mb / 1024)
        else String.format(Locale.US, "%.0f MB", mb)
    }
}

/** Remembers where you stopped in each video. */
object Resume {
    private fun prefs(c: Context) = c.getSharedPreferences("resume", Context.MODE_PRIVATE)

    fun get(c: Context, uri: Uri): Long = prefs(c).getLong(uri.toString(), 0L)

    fun save(c: Context, uri: Uri, positionMs: Long, durationMs: Long) {
        val e = prefs(c).edit()
        if (durationMs <= 0 || positionMs < 5_000 || positionMs > durationMs - 5_000) {
            e.remove(uri.toString())
        } else {
            e.putLong(uri.toString(), positionMs)
        }
        e.apply()
    }
}
