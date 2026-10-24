package com.eol.app

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecInfo.VideoCapabilities.PerformancePoint
import android.media.MediaCodecList
import android.media.MediaFormat

object CodecProbe {
    private val WANTED_TYPES = setOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)

    // The high-speed camera path records at 1280x720 (smallest high-speed size).
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private val FRAME_RATES = listOf(120, 240)

    private val AVC_PROFILES = listOf(
        CodecProfileLevel.AVCProfileBaseline to "Baseline",
        CodecProfileLevel.AVCProfileConstrainedBaseline to "ConstrainedBaseline",
    )

    private val BITRATE_MODES = listOf(
        EncoderCapabilities.BITRATE_MODE_CBR to "CBR",
        EncoderCapabilities.BITRATE_MODE_VBR to "VBR",
        EncoderCapabilities.BITRATE_MODE_CQ to "CQ",
    )

    fun probeCodec(): String = buildString {
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (codecInfo in codecInfos) {
            if (!codecInfo.isAlias && codecInfo.supportedTypes.any { it in WANTED_TYPES }) {
                appendCodec(codecInfo)
            }
        }
    }

    private fun StringBuilder.appendCodec(codecInfo: MediaCodecInfo) {
        val kind = if (codecInfo.isEncoder) "encoder" else "decoder"
        val acceleration = when {
            codecInfo.isHardwareAccelerated -> "hardware"
            codecInfo.isSoftwareOnly -> "software"
            else -> "unknown acceleration"
        }
        appendLine("==== ${codecInfo.name} ($kind, $acceleration)")
        for (type in codecInfo.supportedTypes) {
            if (type in WANTED_TYPES) {
                appendCapabilities(codecInfo, type)
            }
        }
    }

    private fun StringBuilder.appendCapabilities(codecInfo: MediaCodecInfo, mimeType: String) {
        appendLine("-- $mimeType")
        val caps = codecInfo.getCapabilitiesForType(mimeType)
        val video = caps.videoCapabilities
        if (video == null) {
            appendLine("  no video capabilities")
            return
        }

        for (fps in FRAME_RATES) {
            appendLine("  ${WIDTH}x$HEIGHT@$fps supported: ${video.areSizeAndRateSupported(WIDTH, HEIGHT, fps.toDouble())}")
        }
        // Throws if the size itself is unsupported, so check it first.
        if (video.isSizeSupported(WIDTH, HEIGHT)) {
            appendLine("  achievable fps at ${WIDTH}x$HEIGHT: ${video.getAchievableFrameRatesFor(WIDTH, HEIGHT) ?: "not published"}")
        }

        val points = video.supportedPerformancePoints
        if (points == null) {
            appendLine("  performance points: not published")
        } else {
            for (fps in FRAME_RATES) {
                val covering = points.filter { it.covers(PerformancePoint(WIDTH, HEIGHT, fps)) }
                appendLine("  performance points covering ${WIDTH}x$HEIGHT@$fps: ${covering.ifEmpty { "none" }}")
            }
        }

        if (codecInfo.isEncoder) {
            appendLine("  COLOR_FormatSurface: ${CodecCapabilities.COLOR_FormatSurface in caps.colorFormats}")
            // Profile constants are per codec type (AVC and HEVC values overlap), so only check AVC ones on AVC.
            if (mimeType == MediaFormat.MIMETYPE_VIDEO_AVC) {
                val profiles = caps.profileLevels.map { it.profile }.toSet()
                appendLine("  profiles: ${AVC_PROFILES.joinToString { (profile, name) -> "$name=${profile in profiles}" }}")
            }
            val encoderCaps = caps.encoderCapabilities
            if (encoderCaps != null) {
                appendLine("  bitrate modes: ${BITRATE_MODES.joinToString { (mode, name) -> "$name=${encoderCaps.isBitrateModeSupported(mode)}" }}")
            }
        } else {
            appendLine("  FEATURE_LowLatency: ${caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)}")
        }
    }
}
