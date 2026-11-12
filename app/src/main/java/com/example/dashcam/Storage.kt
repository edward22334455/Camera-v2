package com.example.dashcam

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore

/**
 * Clips are saved to Movies/DashCam through MediaStore, so they show up in the
 * Gallery / Google Photos / Files app, and the app can delete its own clips
 * without any storage permission.
 */
object Storage {
    const val RELATIVE_PATH = "Movies/DashCam"

    /** Always try to leave this much free space on the phone itself. */
    private const val MIN_FREE_BYTES = 1L * 1024 * 1024 * 1024

    /** If free space is below this even after deleting old clips, recording stops. */
    private const val CRITICAL_FREE_BYTES = 300L * 1024 * 1024

    private val collection: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    data class Clip(val uri: Uri, val name: String, val size: Long)

    /** All saved clips, oldest first (file names start with a timestamp). */
    fun listClips(context: Context): List<Clip> {
        val clips = mutableListOf<Clip>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("$RELATIVE_PATH/%"),
            "${MediaStore.Video.Media.DISPLAY_NAME} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            while (c.moveToNext()) {
                clips += Clip(
                    ContentUris.withAppendedId(collection, c.getLong(idCol)),
                    c.getString(nameCol) ?: "",
                    c.getLong(sizeCol),
                )
            }
        }
        return clips
    }

    fun usedBytes(context: Context): Long = listClips(context).sumOf { it.size }

    private fun freeBytes(): Long =
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes

    /**
     * Deletes the oldest clips until the saved footage plus the space reserved for the
     * clip(s) currently being recorded fits inside [capBytes], and the phone still has
     * some free space.
     *
     * @param activeFromName name prefix of the clip(s) being recorded now ("DashCam_<stamp>").
     *   Anything at or after it is never deleted. Names sort by time, oldest first.
     * @param reserveBytes estimated size of the clip(s) being recorded now.
     * @return false if the phone is critically low on space even after cleaning up.
     */
    fun enforceCap(
        context: Context,
        capBytes: Long,
        activeFromName: String,
        reserveBytes: Long,
    ): Boolean {
        val deletable = listClips(context).filter { it.name < activeFromName }
        var total = deletable.sumOf { it.size } + reserveBytes
        var free = freeBytes()

        for (clip in deletable) {
            if (total <= capBytes && free >= MIN_FREE_BYTES + reserveBytes) break
            val deleted = try {
                context.contentResolver.delete(clip.uri, null, null) > 0
            } catch (e: SecurityException) {
                false // e.g. a clip left over from a previous install
            }
            if (deleted) {
                total -= clip.size
                free += clip.size
            }
        }
        return free >= CRITICAL_FREE_BYTES
    }
}
