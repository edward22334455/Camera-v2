package com.example.dashcam

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DashCamScreen()
                }
            }
        }
    }
}

@Composable
private fun DashCamScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }

    var maxGb by remember { mutableIntStateOf(prefs.maxStorageGb) }
    var segmentMin by remember { mutableIntStateOf(prefs.segmentMinutes) }
    var cameraIds by remember { mutableStateOf(prefs.cameraIds) }
    var cameraOptions by remember { mutableStateOf(emptyList<CameraOption>()) }
    var usbDevices by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf(prefs.quality) }
    var audio by remember { mutableStateOf(prefs.audio) }
    var usedBytes by remember { mutableLongStateOf(0L) }
    var minimizeOnStart by remember { mutableStateOf(prefs.minimizeOnStart) }
    var batteryUnrestricted by remember { mutableStateOf(true) }

    val running = RecorderState.running

    // Refresh "space used" every few seconds.
    LaunchedEffect(running) {
        while (true) {
            usedBytes = withContext(Dispatchers.IO) { Storage.usedBytes(context) }
            batteryUnrestricted = isBatteryUnrestricted(context)
            val (options, usb) = withContext(Dispatchers.IO) {
                Cameras.list(context) to Cameras.usbVideoDeviceCount(context)
            }
            cameraOptions = options
            usbDevices = usb
            delay(5_000)
        }
    }

    val permissions = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            startRecording(context)
            // Drop back to the home screen so you can open music, audiobooks or maps.
            if (prefs.minimizeOnStart) (context as? Activity)?.moveTaskToBack(true)
        } else {
            Toast.makeText(context, "Camera permission is needed to record", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("DashCam", style = MaterialTheme.typography.headlineMedium)

        Button(
            onClick = {
                if (running) stopRecording(context) else launcher.launch(permissions)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (running) Color(0xFFB3261E) else Color(0xFF2E7D32),
                contentColor = Color.White,
            ),
        ) {
            Text(if (running) "Stop recording" else "Start recording", style = MaterialTheme.typography.titleLarge)
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(RecorderState.status, style = MaterialTheme.typography.titleMedium)
            if (running && RecorderState.segmentStartedAt > 0) {
                val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(RecorderState.segmentStartedAt))
                Text("Current clip started at $t")
            }
            if (RecorderState.notice.isNotEmpty()) {
                Text(RecorderState.notice, color = MaterialTheme.colorScheme.error)
            }
            Text("Saved clips: ${formatGb(usedBytes)} of $maxGb GB used")
            Text("Location: Movies/DashCam", style = MaterialTheme.typography.bodySmall)
        }

        Section("Storage limit: $maxGb GB") {
            Slider(
                value = maxGb.toFloat(),
                onValueChange = { maxGb = it.roundToInt() },
                onValueChangeFinished = { prefs.maxStorageGb = maxGb },
                valueRange = 2f..256f,
                enabled = !running,
            )
            Text(
                "When saved clips reach this limit, the oldest ones are deleted automatically.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Cameras (pick one or two)") {
            CameraChip("Default: back + front", cameraIds.isEmpty(), !running) {
                cameraIds = emptyList()
                prefs.cameraIds = cameraIds
            }
            cameraOptions.forEach { option ->
                CameraChip(option.label, option.id in cameraIds, !running) {
                    val updated = if (option.id in cameraIds) {
                        cameraIds - option.id
                    } else {
                        (cameraIds + option.id).takeLast(2)
                    }
                    cameraIds = updated
                    prefs.cameraIds = updated
                }
            }
            Text(
                "The first camera you pick also records the sound. Two cameras at once only works " +
                    "if your phone supports that pair; otherwise only the first records and the app tells you.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (usbDevices > 0 && cameraOptions.none { it.external }) {
                Text(
                    "A USB camera is plugged in, but your phone isn't offering it to apps as a camera, " +
                        "so DashCam can't use it. See the README.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else if (cameraOptions.none { it.external }) {
                Text(
                    "USB cameras only appear here if your phone supports them. Plug it in and it should show up.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Section("Quality") {
            Chips(
                options = listOf("720p" to "720p", "1080p" to "1080p"),
                selected = quality,
                enabled = !running,
            ) {
                quality = it
                prefs.quality = it
            }
            Text(
                "Most phones limit each camera to about 720p when both record together.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Clip length") {
            Chips(
                options = listOf("5" to "5 min", "10" to "10 min", "15" to "15 min"),
                selected = segmentMin.toString(),
                enabled = !running,
            ) {
                segmentMin = it.toInt()
                prefs.segmentMinutes = segmentMin
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Record sound (from the back camera clip)")
            Switch(
                checked = audio,
                onCheckedChange = { audio = it; prefs.audio = it },
                enabled = !running,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Go to home screen after starting")
            Switch(
                checked = minimizeOnStart,
                onCheckedChange = { minimizeOnStart = it; prefs.minimizeOnStart = it },
            )
        }

        Section("Keep recording in the background") {
            Text(
                "Recording continues while you use music, audiobooks, maps or any other app, " +
                    "and with the screen off. To stop, reopen DashCam or use Stop in its notification.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (batteryUnrestricted) {
                Text("Battery optimisation is off for DashCam. Good.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "Android may stop long recordings to save battery. Allow DashCam to run unrestricted:",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = { requestBatteryExemption(context) }) {
                    Text("Allow background running")
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun CameraChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Chips(
    options: List<Pair<String, String>>,
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)
        .isIgnoringBatteryOptimizations(context.packageName)

private fun requestBatteryExemption(context: Context) {
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            )
        )
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

private fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

private fun startRecording(context: Context) {
    ContextCompat.startForegroundService(
        context,
        Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START),
    )
}

private fun stopRecording(context: Context) {
    context.startService(
        Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP)
    )
}
