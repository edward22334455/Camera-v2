package com.example.dashcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the UI shows. Only touched from the main thread. */
object RecorderState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("Not recording")
    var notice by mutableStateOf("")
    var segmentStartedAt by mutableLongStateOf(0L)
}

/**
 * Foreground service that records the chosen camera(s) in fixed-length clips and keeps
 * the saved footage under the user's storage limit by deleting the oldest clips.
 */
class RecordingService : LifecycleService() {

    private class Cam(
        val label: String,
        val selector: CameraSelector,
        val capture: VideoCapture<Recorder>,
        val withAudio: Boolean,
    ) {
        var recording: Recording? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var provider: ProcessCameraProvider? = null
    private val cams = mutableListOf<Cam>()
    private var wakeLock: PowerManager.WakeLock? = null

    private var running = false
    private var stopping = false
    private var finalStatus = "Stopped"
    private var openFinalizes = 0
    private var segmentHadError = false
    private var consecutiveErrors = 0
    private var segmentStamp = ""
    private var segmentBytesEstimate = 0L

    private val rotate = Runnable { endSegment() }

    private val capCheck = object : Runnable {
        override fun run() {
            if (!running || stopping) return
            val ok = Storage.enforceCap(
                this@RecordingService, prefs.maxStorageBytes,
                "DashCam_$segmentStamp", segmentBytesEstimate,
            )
            if (!ok) requestStop("Stopped: phone storage is almost full.")
            else handler.postDelayed(this, CAP_CHECK_MS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> requestStop("Stopped")
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- start

    private fun startRecording() {
        if (running) return
        running = true
        stopping = false
        finalStatus = "Stopped"
        consecutiveErrors = 0
        prefs = Prefs(this)

        goForeground()
        acquireWakeLock()
        RecorderState.running = true
        RecorderState.status = "Starting cameras…"
        RecorderState.notice = ""

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                bindCameras(future.get())
                startSegment()
                handler.postDelayed(capCheck, CAP_CHECK_MS)
            } catch (e: Exception) {
                requestStop("Couldn't start cameras: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun hasMic() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Dash cam recording", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Dash cam is recording")
            .setContentText("Tap to open")
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .build()

        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (prefs.audio && hasMic()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DashCam:recording").apply { acquire() }
    }

    /** Picks one specific camera by its ID (works for back lenses, front and USB cameras). */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun selectorFor(id: String): CameraSelector {
        val builder = CameraSelector.Builder()
        Cameras.find(this, id)?.let { builder.requireLensFacing(it.lensFacing) }
        return builder
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).cameraId == id } }
            .build()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameras(p: ProcessCameraProvider) {
        provider = p
        p.unbindAll()

        fun idOf(info: CameraInfo) = Camera2CameraInfo.from(info).cameraId

        val concurrentGroups = p.availableConcurrentCameraInfos.map { group -> group.map { idOf(it) } }
        val availableIds = p.availableCameraInfos.map { idOf(it) }.toSet()
        val chosen = prefs.cameraIds
        val notices = mutableListOf<String>()

        val lenses: List<Pair<String, CameraSelector>>
        if (chosen.isEmpty()) {
            // Default: back + front, if the phone can run both at once.
            val canRunBoth = concurrentGroups.any { it.size >= 2 }
            lenses = if (canRunBoth) {
                listOf("back" to CameraSelector.DEFAULT_BACK_CAMERA, "front" to CameraSelector.DEFAULT_FRONT_CAMERA)
            } else {
                notices += "This phone can't run both cameras at once, so only the back camera is recording."
                listOf("back" to CameraSelector.DEFAULT_BACK_CAMERA)
            }
        } else {
            var wanted = chosen.filter { it in availableIds }
            if (wanted.size < chosen.size) {
                notices += "A selected camera isn't available right now (a USB camera must be plugged in before you start)."
            }
            if (wanted.size == 2 && concurrentGroups.none { group -> wanted.all { it in group } }) {
                notices += "This phone can't run those two cameras at the same time, so only the first one is recording."
                wanted = wanted.take(1)
            }
            lenses = if (wanted.isEmpty()) {
                notices += "Recording with the back camera instead."
                listOf("back" to CameraSelector.DEFAULT_BACK_CAMERA)
            } else {
                wanted.map { id -> (Cameras.find(this, id)?.tag ?: "cam-$id") to selectorFor(id) }
            }
        }

        // Phones that support two cameras at once generally limit each to ~720p.
        val quality = if (lenses.size == 2 || prefs.quality == "720p") Quality.HD else Quality.FHD
        val videoBitrate = if (quality == Quality.FHD) 8_000_000 else 4_000_000

        cams.clear()
        lenses.forEachIndexed { index, (label, selector) ->
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD))
                )
                .setTargetVideoEncodingBitRate(videoBitrate)
                .build()
            // Only one camera records sound (the first one chosen).
            val audio = index == 0 && prefs.audio && hasMic()
            cams += Cam(label, selector, VideoCapture.withOutput(recorder), audio)
        }

        if (cams.size == 2) {
            try {
                p.bindToLifecycle(
                    cams.map {
                        ConcurrentCamera.SingleCameraConfig(
                            it.selector,
                            UseCaseGroup.Builder().addUseCase(it.capture).build(),
                            this,
                        )
                    }
                )
            } catch (e: Exception) {
                // The phone refused the pair: keep going with the first camera only.
                p.unbindAll()
                val first = cams[0]
                cams.clear()
                cams += first
                notices += "Couldn't run both cameras together, so only ${first.label} is recording."
                p.bindToLifecycle(this, first.selector, first.capture)
            }
        } else {
            p.bindToLifecycle(this, cams[0].selector, cams[0].capture)
        }

        RecorderState.notice = notices.joinToString(" ")

        val perCamBytes = (videoBitrate + 128_000L) / 8L * prefs.segmentMinutes * 60L
        segmentBytesEstimate = perCamBytes * cams.size
    }

    // ------------------------------------------------------------- segments

    @SuppressLint("MissingPermission") // checked via hasMic()
    private fun startSegment() {
        if (!running || stopping) return

        segmentStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ok = Storage.enforceCap(
            this, prefs.maxStorageBytes, "DashCam_$segmentStamp", segmentBytesEstimate
        )
        if (!ok) {
            requestStop("Stopped: phone storage is almost full.")
            return
        }

        segmentHadError = false
        openFinalizes = cams.size
        for (cam in cams) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "DashCam_${segmentStamp}_${cam.label}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Storage.RELATIVE_PATH)
            }
            val options = MediaStoreOutputOptions.Builder(
                contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            ).setContentValues(values).build()

            var pending = cam.capture.output.prepareRecording(this, options)
            if (cam.withAudio) pending = pending.withAudioEnabled()
            cam.recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                onRecordEvent(cam, event)
            }
        }

        RecorderState.segmentStartedAt = System.currentTimeMillis()
        RecorderState.status = "Recording"
        handler.postDelayed(rotate, prefs.segmentMinutes * 60_000L)
    }

    /** Stops the current clips; the next clip starts once they are all saved. */
    private fun endSegment() {
        handler.removeCallbacks(rotate)
        openFinalizes = cams.count { it.recording != null }
        if (openFinalizes == 0) {
            onSegmentClosed()
        } else {
            cams.forEach { it.recording?.stop() }
        }
    }

    private fun onRecordEvent(cam: Cam, event: VideoRecordEvent) {
        if (event is VideoRecordEvent.Finalize) {
            cam.recording = null
            if (event.hasError()) segmentHadError = true
            openFinalizes--
            if (openFinalizes <= 0) onSegmentClosed()
        }
    }

    private fun onSegmentClosed() {
        if (stopping) {
            shutdown()
            return
        }
        if (segmentHadError) consecutiveErrors++ else consecutiveErrors = 0
        if (consecutiveErrors >= 5) {
            requestStop("Stopped: recording kept failing. Check camera access.")
            return
        }
        // If something else grabbed the camera (a call, another app), wait a moment and retry.
        if (segmentHadError) handler.postDelayed({ startSegment() }, RETRY_DELAY_MS) else startSegment()
    }

    // ----------------------------------------------------------------- stop

    private fun requestStop(message: String) {
        if (!running || stopping) return
        stopping = true
        finalStatus = message
        RecorderState.status = "Saving last clip…"
        handler.removeCallbacks(rotate)
        handler.removeCallbacks(capCheck)
        openFinalizes = cams.count { it.recording != null }
        if (openFinalizes == 0) shutdown() else cams.forEach { it.recording?.stop() }
    }

    private fun shutdown() {
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        running = false
        RecorderState.running = false
        RecorderState.status = finalStatus
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        provider?.unbindAll()
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        RecorderState.running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.example.dashcam.START"
        const val ACTION_STOP = "com.example.dashcam.STOP"
        private const val CHANNEL_ID = "dashcam_recording"
        private const val NOTIF_ID = 1
        private const val CAP_CHECK_MS = 30_000L
        private const val RETRY_DELAY_MS = 3_000L
    }
}
