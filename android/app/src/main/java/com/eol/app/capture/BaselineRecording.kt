package com.eol.app.capture

import android.Manifest
import android.content.Context
import android.util.Size
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit
import kotlin.time.TimeSource

data class BaselineResult(val file: File, val failedCaptures: Int, val lostBuffers: Int)

suspend fun BaselineCapture.stopAndWait() =
    suspendCancellableCoroutine { cont -> stop { cont.resume(Unit) } }

@RequiresPermission(Manifest.permission.CAMERA)
suspend fun recordBaseline(
    context: Context,
    cameraId: String,
    seconds: Int,
    onProgress: (Double) -> Unit,
): BaselineResult {
    val ts = TimeSource.Monotonic
    val logger = FrameLog(BaselineCapture.FPS, seconds)
    val capture = BaselineCapture(context, logger)
    var stopped = false
    try {
        val filesDir =
            context.getExternalFilesDir(null) ?: throw IOException("External storage unavailable")
        val baselineDir = File(filesDir, "baseline").apply { mkdirs() }
        val startedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val resultFile = File(
            baselineDir,
            "baseline_cam${cameraId}_${BaselineCapture.FPS}fps_${seconds}sec_$startedAt.csv",
        )

        capture.start(cameraId = cameraId, Size(1280, 720))
        val startTime = ts.markNow()
        do {
            val elapsed =
                (ts.markNow() - startTime).toDouble(DurationUnit.SECONDS)
            onProgress(elapsed / seconds)
            delay(100.milliseconds)
        } while (elapsed < seconds)
        capture.stopAndWait()
        stopped = true
        withContext(Dispatchers.IO) {
            logger.writeCsv(resultFile)
        }
        return BaselineResult(
            file = resultFile,
            failedCaptures = capture.failedCaptures,
            lostBuffers = capture.lostBuffers
        )
    } finally {
        if (!stopped) capture.stop()
    }
}