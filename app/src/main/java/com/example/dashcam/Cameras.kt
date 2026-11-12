package com.example.dashcam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import androidx.camera.core.CameraSelector
import kotlin.math.hypot
import kotlin.math.roundToInt

/** One camera the phone offers to apps. */
data class CameraOption(
    val id: String,
    /** Shown in the picker, e.g. "Back - Ultra-wide, ~14mm (id 2)". */
    val label: String,
    /** Used in clip file names, e.g. "back-2". */
    val tag: String,
    /** One of CameraSelector.LENS_FACING_*. */
    val lensFacing: Int,
    val external: Boolean,
)

object Cameras {

    /** Every usable camera: back lenses, front, then USB/external ones. */
    fun list(context: Context): List<CameraOption> {
        val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        val ids = try {
            manager.cameraIdList
        } catch (e: Exception) {
            return emptyList()
        }

        val out = mutableListOf<CameraOption>()
        for (id in ids) {
            val c = try {
                manager.getCameraCharacteristics(id)
            } catch (e: Exception) {
                continue
            }

            // Skip depth / infrared / monochrome helper sensors that can't record normal video.
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            if (caps != null && CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue

            val word: String
            val facing: Int
            when (c.get(CameraCharacteristics.LENS_FACING)) {
                CameraCharacteristics.LENS_FACING_BACK -> { word = "back"; facing = CameraSelector.LENS_FACING_BACK }
                CameraCharacteristics.LENS_FACING_FRONT -> { word = "front"; facing = CameraSelector.LENS_FACING_FRONT }
                CameraCharacteristics.LENS_FACING_EXTERNAL -> { word = "usb"; facing = CameraSelector.LENS_FACING_EXTERNAL }
                else -> continue
            }

            val lens = lensName(c)
            val label = when (word) {
                "back" -> "Back" + (lens?.let { " - $it" } ?: "") + " (id $id)"
                "front" -> "Front" + (lens?.let { " - $it" } ?: "") + " (id $id)"
                else -> "USB / external camera (id $id)"
            }
            out += CameraOption(id, label, "$word-$id", facing, word == "usb")
        }

        val order = mapOf(
            CameraSelector.LENS_FACING_BACK to 0,
            CameraSelector.LENS_FACING_FRONT to 1,
            CameraSelector.LENS_FACING_EXTERNAL to 2,
        )
        return out.sortedWith(compareBy({ order[it.lensFacing] ?: 3 }, { it.id }))
    }

    fun find(context: Context, id: String): CameraOption? = list(context).firstOrNull { it.id == id }

    /** "Ultra-wide, ~14mm" from the lens focal length and sensor size (35mm-equivalent). */
    private fun lensName(c: CameraCharacteristics): String? {
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val diagonal = hypot(size.width.toDouble(), size.height.toDouble())
        if (diagonal <= 0.0) return null
        val equivalent = (focal * 43.27 / diagonal).roundToInt()
        val kind = when {
            equivalent <= 18 -> "Ultra-wide"
            equivalent <= 40 -> "Main"
            else -> "Telephoto"
        }
        return "$kind, ~${equivalent}mm"
    }

    /** How many plugged-in USB devices identify as video (webcam / UVC) devices. */
    fun usbVideoDeviceCount(context: Context): Int {
        val usb = context.getSystemService(UsbManager::class.java) ?: return 0
        return usb.deviceList.values.count { dev ->
            dev.deviceClass == UsbConstants.USB_CLASS_VIDEO ||
                (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
        }
    }
}
