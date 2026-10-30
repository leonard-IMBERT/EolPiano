package com.eol.app.capture

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor

/**
 * Streams one camera at a fixed 30 fps into a YUV ImageReader and logs when each image and
 * each capture result reach the app. Single use: create a new instance for every run.
 *
 * Every camera callback runs on one handler thread, so the fields below need no locking.
 */
class BaselineCapture(context: Context, private val log: FrameLog) {

    companion object {
        private const val TAG = "Eol.baseline"
        const val FPS = 30
        private const val MAX_IMAGES = 4

        private val CAPTURE_INTENT = mapOf(
            CameraMetadata.CONTROL_CAPTURE_INTENT_CUSTOM to "CUSTOM",
            CameraMetadata.CONTROL_CAPTURE_INTENT_PREVIEW to "PREVIEW",
            CameraMetadata.CONTROL_CAPTURE_INTENT_STILL_CAPTURE to "STILL_CAPTURE",
            CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_RECORD to "VIDEO_RECORD",
            CameraMetadata.CONTROL_CAPTURE_INTENT_VIDEO_SNAPSHOT to "VIDEO_SNAPSHOT",
            CameraMetadata.CONTROL_CAPTURE_INTENT_ZERO_SHUTTER_LAG to "ZERO_SHUTTER_LAG",
            CameraMetadata.CONTROL_CAPTURE_INTENT_MANUAL to "MANUAL",
        )

        private val STABILIZATION_MODE = mapOf(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF to "OFF",
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON to "ON",
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION to "PREVIEW_STABILIZATION",
        )

        // NOISE_REDUCTION_MODE and EDGE_MODE share these values.
        private val PROCESSING_MODE = mapOf(
            CameraMetadata.NOISE_REDUCTION_MODE_OFF to "OFF",
            CameraMetadata.NOISE_REDUCTION_MODE_FAST to "FAST",
            CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY to "HIGH_QUALITY",
            CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL to "MINIMAL",
            CameraMetadata.NOISE_REDUCTION_MODE_ZERO_SHUTTER_LAG to "ZERO_SHUTTER_LAG",
        )
    }

    private val cameraManager = context.getSystemService(CameraManager::class.java)

    private val thread = HandlerThread("BaselineCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var imageReader: ImageReader? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    // Set by stop(); callbacks still queued behind it must not touch closed objects or the log.
    private var stopped = false

    // The camera's own explanation for missing frames.
    @Volatile
    var failedCaptures = 0
        private set

    @Volatile
    var lostBuffers = 0
        private set

    @RequiresPermission(Manifest.permission.CAMERA)
    fun start(cameraId: String, size: Size) {
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, MAX_IMAGES)
        reader.setOnImageAvailableListener(::onImageAvailable, handler)
        imageReader = reader
        cameraManager.openCamera(cameraId, executor, deviceCallback)
    }

    /**
     * Closes the camera and quits the thread. [onStopped] runs on the camera thread after the last
     * write to the log, so it is the place to write the CSV.
     */
    fun stop(onStopped: () -> Unit = {}) {
        handler.post {
            stopped = true
            session?.close()
            device?.close()
            imageReader?.close()
            session = null
            device = null
            imageReader = null
            onStopped()
            thread.quitSafely()
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        val arrivalTs = SystemClock.elapsedRealtimeNanos()
        if (stopped) return
        // Several images can be queued per callback; equal arrivalTs values in the log reveal it.
        while (true) {
            val image = reader.acquireNextImage() ?: break
            log.pushImageLog(image.timestamp, arrivalTs)
            image.close()
        }
    }

    private val deviceCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            if (stopped) {
                camera.close()
                return
            }
            device = camera
            val reader = imageReader ?: return
            camera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(reader.surface)),
                    executor,
                    sessionCallback,
                )
            )
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "camera ${camera.id} disconnected")
            camera.close()
            device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "camera ${camera.id} error $error")
            camera.close()
            device = null
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(s: CameraCaptureSession) {
            val reader = imageReader
            if (stopped || reader == null) {
                s.close()
                return
            }
            session = s
            val request = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                // Fixed range: AE must not drop to 15 fps in dim light.
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(FPS, FPS))
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()
            s.setSingleRepeatingRequest(request, executor, captureCallback)
        }

        override fun onConfigureFailed(s: CameraCaptureSession) {
            Log.e(TAG, "session configuration failed")
        }
    }

    // The camera may silently ignore a requested setting; the first result shows what it applied.
    private var settingsLogged = false

    private fun logSettings(request: CaptureRequest, result: CaptureResult) {
        fun line(name: String, requested: Any?, applied: Any?, names: Map<Int, String> = emptyMap()) {
            fun show(v: Any?) = (v as? Int)?.let { names[it] } ?: v.toString()
            Log.i(TAG, "$name: requested ${show(requested)}, applied ${show(applied)}")
        }
        line(
            "capture intent",
            request.get(CaptureRequest.CONTROL_CAPTURE_INTENT),
            result.get(CaptureResult.CONTROL_CAPTURE_INTENT),
            CAPTURE_INTENT,
        )
        line(
            "AE target fps range",
            request.get(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE),
            result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE),
        )
        line(
            "video stabilization",
            request.get(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE),
            result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE),
            STABILIZATION_MODE,
        )
        line(
            "noise reduction",
            request.get(CaptureRequest.NOISE_REDUCTION_MODE),
            result.get(CaptureResult.NOISE_REDUCTION_MODE),
            PROCESSING_MODE,
        )
        line(
            "edge",
            request.get(CaptureRequest.EDGE_MODE),
            result.get(CaptureResult.EDGE_MODE),
            PROCESSING_MODE,
        )
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) {
            val resultTs = SystemClock.elapsedRealtimeNanos()
            if (stopped) return
            if (!settingsLogged) {
                settingsLogged = true
                logSettings(request, result)
            }
            log.pushResultLog(
                sensorTs = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: -1,
                resultTs = resultTs,
                exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: -1,
                frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: -1,
            )
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure,
        ) {
            failedCaptures++
        }

        override fun onCaptureBufferLost(
            session: CameraCaptureSession,
            request: CaptureRequest,
            target: Surface,
            frameNumber: Long,
        ) {
            lostBuffers++
        }
    }
}
