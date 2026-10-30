package com.eol.app

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.params.StreamConfigurationMap
import kotlin.math.roundToInt

object CameraProbe {
    val FACING = mapOf(
        CameraMetadata.LENS_FACING_FRONT to "front",
        CameraMetadata.LENS_FACING_BACK to "back",
        CameraMetadata.LENS_FACING_EXTERNAL to "external",
    )

    private val HARDWARE_LEVEL = mapOf(
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY to "LEGACY",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED to "LIMITED",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL to "FULL",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 to "LEVEL_3",
        CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL to "EXTERNAL",
    )

    private val TIMESTAMP_SOURCE = mapOf(
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN to "UNKNOWN",
        CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME to "REALTIME",
    )

    // Only the capabilities that matter for this project; others print as numbers.
    private val CAPABILITY = mapOf(
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE to "BACKWARD_COMPATIBLE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR to "MANUAL_SENSOR",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING to "MANUAL_POST_PROCESSING",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW to "RAW",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS to "READ_SENSOR_SETTINGS",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE to "BURST_CAPTURE",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO to "CONSTRAINED_HIGH_SPEED_VIDEO",
        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
    )

    // Normal-mode output formats whose per-size maximum frame rate is reported.
    private val FORMATS = listOf(
        ImageFormat.YUV_420_888 to "YUV_420_888",
        ImageFormat.PRIVATE to "PRIVATE",
    )

    fun probeCamera(context: Context): String = buildString {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        for (id in cameraManager.cameraIdList) {
            appendCamera(id, cameraManager.getCameraCharacteristics(id))
        }
    }

    private fun StringBuilder.appendCamera(id: String, chars: CameraCharacteristics) {
        appendLine("==== Camera $id")
        appendLine("facing: ${FACING.nameOf(chars.get(CameraCharacteristics.LENS_FACING))}")
        appendLine("hardware level: ${HARDWARE_LEVEL.nameOf(chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))}")
        appendLine(
            "focal lengths (mm): ${
                chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.contentToString()
            }"
        )
        appendLine("sensor orientation: ${chars.get(CameraCharacteristics.SENSOR_ORIENTATION)}")
        appendLine("timestamp source: ${TIMESTAMP_SOURCE.nameOf(chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE))}")

        val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        appendLine("capabilities: ${capabilities?.joinToString { CAPABILITY.nameOf(it) }}")

        val exposure = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exposure != null) {
            appendLine("exposure time: ${exposure.lower / 1000} us to ${exposure.upper / 1000} us")
        }
        appendLine("sensitivity (ISO): ${chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)}")
        appendLine(
            "AE target fps ranges: ${
                chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.contentToString()
            }"
        )

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        if (map != null) {
            appendStreamMap(map)
        }
    }

    private fun StringBuilder.appendStreamMap(map: StreamConfigurationMap) {
        appendLine("-- High-speed video sizes and fps ranges")
        val highSpeedSizes = map.highSpeedVideoSizes.sortedBy { it.width * it.height }
        if (highSpeedSizes.isEmpty()) {
            appendLine("  none")
        }
        for (size in highSpeedSizes) {
            appendLine("  $size: ${map.getHighSpeedVideoFpsRangesFor(size).contentToString()}")
        }

        for ((format, name) in FORMATS) {
            appendLine("-- $name sizes, max fps from min frame duration")
            val sizes = map.getOutputSizes(format).orEmpty().sortedBy { it.width * it.height }
            for (size in sizes) {
                val durationNs = map.getOutputMinFrameDuration(format, size)
                appendLine("  $size: ${maxFps(durationNs)} fps ($durationNs ns)")
            }
        }
    }

    private fun maxFps(durationNs: Long): String =
        if (durationNs > 0) (1e9 / durationNs).roundToInt().toString() else "?"

    private fun Map<Int, String>.nameOf(value: Int?): String =
        if (value == null) "n/a" else this[value] ?: "unknown ($value)"
}
