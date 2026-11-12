package com.example.dashcam

import android.content.Context

/** Simple settings storage. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("dashcam", Context.MODE_PRIVATE)

    /** Max space (GB) the app may use for saved clips. Oldest clips are deleted beyond this. */
    var maxStorageGb: Int
        get() = sp.getInt("max_gb", 16)
        set(v) = sp.edit().putInt("max_gb", v).apply()

    /** Length of each clip in minutes. */
    var segmentMinutes: Int
        get() = sp.getInt("segment_min", 10)
        set(v) = sp.edit().putInt("segment_min", v).apply()

    /**
     * Chosen camera IDs (one or two). The first one also records sound.
     * Empty means the default: back + front camera.
     */
    var cameraIds: List<String>
        get() = (sp.getString("camera_ids", "") ?: "").split(",").filter { it.isNotBlank() }
        set(v) = sp.edit().putString("camera_ids", v.joinToString(",")).apply()

    /** "720p" or "1080p" (1080p only applies when recording a single camera). */
    var quality: String
        get() = sp.getString("quality", "720p") ?: "720p"
        set(v) = sp.edit().putString("quality", v).apply()

    var audio: Boolean
        get() = sp.getBoolean("audio", true)
        set(v) = sp.edit().putBoolean("audio", v).apply()

    /** Send the app to the background right after recording starts (so you land on your home screen). */
    var minimizeOnStart: Boolean
        get() = sp.getBoolean("minimize", true)
        set(v) = sp.edit().putBoolean("minimize", v).apply()

    val maxStorageBytes: Long get() = maxStorageGb * 1024L * 1024L * 1024L
}
