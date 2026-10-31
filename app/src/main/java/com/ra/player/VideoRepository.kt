package com.ra.player

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

object VideoRepository {

    /** Reads every video on the device from MediaStore, newest first. Call off the main thread. */
    fun load(context: Context): List<VideoItem> {
        val result = ArrayList<VideoItem>()
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            while (c.moveToNext()) {
                val duration = c.getLong(durCol)
                if (duration <= 0) continue
                val name = c.getString(nameCol) ?: "Video"
                result.add(
                    VideoItem(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        title = name.substringBeforeLast('.'),
                        durationMs = duration,
                        sizeBytes = c.getLong(sizeCol),
                        folder = c.getString(folderCol) ?: "",
                        width = c.getInt(wCol),
                        height = c.getInt(hCol)
                    )
                )
            }
        }
        return result
    }
}
