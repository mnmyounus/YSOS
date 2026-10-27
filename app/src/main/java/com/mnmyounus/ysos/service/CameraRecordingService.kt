package com.mnmyounus.ysos.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Size
import com.mnmyounus.ysos.security.EncryptionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Records video from the back camera and, on phones that allow it, the front camera at the
 * same time (Camera2 API).
 *
 * Most phones cannot open front + back cameras simultaneously. Android 11+ tells us which
 * combinations are allowed (CameraManager.getConcurrentCameraIds); if the pair is not
 * allowed, only the back camera is recorded.
 *
 * Every camera callback is wrapped so a hardware/permission error can never crash the app.
 */
class CameraRecordingService : Service() {

    /** State for one camera being recorded. */
    private class Cam(val id: String, val label: String, val withAudio: Boolean) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var recorder: MediaRecorder? = null
        var file: File? = null
        var recording = false
        var released = false
        var chained = false
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private val cams = CopyOnWriteArrayList<Cam>()

    companion object {
        private const val TAG = "YSOSCamera"
        const val ACTION_START = "com.mnmyounus.ysos.action.CAMERA_START"
        const val ACTION_STOP  = "com.mnmyounus.ysos.action.CAMERA_STOP"

        fun startRecording(context: Context) {
            context.startService(Intent(context, CameraRecordingService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stopRecording(context: Context) {
            context.startService(Intent(context, CameraRecordingService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onCreate() {
        super.onCreate()
        val thread = HandlerThread("CameraThread")
        thread.start()
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP  -> stopCapture()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------------
    // Start
    // ---------------------------------------------------------------------------------------------

    private fun startCapture() {
        val handler = backgroundHandler
        if (handler == null) {
            stopSelf()
            return
        }
        handler.post { beginRecording() }
    }

    @SuppressLint("MissingPermission")
    private fun beginRecording() {
        if (cams.isNotEmpty()) return
        try {
            val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
            val dir = File(filesDir, "evidence").apply { mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            var backId: String? = null
            var frontId: String? = null
            for (id in mgr.cameraIdList) {
                val facing = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK && backId == null) backId = id
                if (facing == CameraCharacteristics.LENS_FACING_FRONT && frontId == null) frontId = id
            }
            val back = backId
            val front = frontId

            val both = back != null && front != null && supportsConcurrent(mgr, back, front)
            if (back != null && front != null && !both) {
                Log.i(TAG, "This phone cannot run front + back cameras together: recording back camera only")
            }

            // Back camera carries the microphone track. Front camera (if used) is video-only so the
            // two recorders never fight over the microphone.
            val first: Cam? = if (back != null) Cam(back, "back", true) else null
            val second: Cam? = if (front != null && (back == null || both)) Cam(front, "front", back == null) else null

            if (first == null && second == null) {
                Log.w(TAG, "No camera available")
                return
            }
            if (first != null) cams.add(first)
            if (second != null) cams.add(second)

            if (first != null) {
                // Start the second camera only after the first one is up (or has failed).
                openCam(mgr, first, dir, ts) {
                    if (second != null) openCam(mgr, second, dir, ts, null)
                }
            } else if (second != null) {
                openCam(mgr, second, dir, ts, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start camera recording", e)
        }
    }

    private fun supportsConcurrent(mgr: CameraManager, a: String, b: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return try {
            mgr.getConcurrentCameraIds().any { it.contains(a) && it.contains(b) }
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCam(mgr: CameraManager, cam: Cam, dir: File, ts: String, onFinished: (() -> Unit)?) {
        try {
            val raw = File(dir, "cam_${cam.label}_$ts.mp4")
            cam.file = raw
            cam.recorder = buildRecorder(mgr, cam, raw)

            mgr.openCamera(cam.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cam.released) {
                        camera.close()
                        return
                    }
                    cam.device = camera
                    configureSession(cam, camera, onFinished)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "${cam.label} camera disconnected")
                    camera.close()
                    releaseCam(cam)
                    chain(cam, onFinished)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.w(TAG, "${cam.label} camera error $error")
                    camera.close()
                    releaseCam(cam)
                    chain(cam, onFinished)
                }
            }, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Could not open ${cam.label} camera", e)
            releaseCam(cam)
            chain(cam, onFinished)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSession(cam: Cam, camera: CameraDevice, onFinished: (() -> Unit)?) {
        try {
            val recorder = cam.recorder ?: throw IllegalStateException("Recorder missing")
            val surface = recorder.surface
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }.build()

            camera.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (cam.released) {
                            try { session.close() } catch (_: Exception) {}
                            return
                        }
                        cam.session = session
                        try {
                            session.setRepeatingRequest(request, null, backgroundHandler)
                            recorder.start()
                            cam.recording = true
                            Log.i(TAG, "${cam.label} camera recording started")
                        } catch (e: Exception) {
                            Log.e(TAG, "${cam.label} camera could not start recording", e)
                            releaseCam(cam)
                        }
                        chain(cam, onFinished)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.w(TAG, "${cam.label} camera session configuration failed")
                        releaseCam(cam)
                        chain(cam, onFinished)
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "${cam.label} camera session error", e)
            releaseCam(cam)
            chain(cam, onFinished)
        }
    }

    /** Runs the "next camera" step exactly once per camera. */
    private fun chain(cam: Cam, onFinished: (() -> Unit)?) {
        if (cam.chained) return
        cam.chained = true
        try {
            onFinished?.invoke()
        } catch (e: Exception) {
            Log.e(TAG, "Follow-up camera step failed", e)
        }
    }

    private fun chooseVideoSize(map: StreamConfigurationMap?): Size {
        val sizes = map?.getOutputSizes(MediaRecorder::class.java) ?: return Size(1280, 720)
        val exact = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
        if (exact != null) return exact
        val fitting = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
        if (fitting != null) return fitting
        return sizes.minByOrNull { it.width * it.height } ?: Size(1280, 720)
    }

    private fun buildRecorder(mgr: CameraManager, cam: Cam, outputFile: File): MediaRecorder {
        val chars = mgr.getCameraCharacteristics(cam.id)
        val size = chooseVideoSize(chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        // Without this hint the video plays sideways in every player.
        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            if (cam.withAudio) recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(outputFile.absolutePath)
            recorder.setVideoEncodingBitRate(if (size.width >= 1280) 4_000_000 else 2_000_000)
            recorder.setVideoFrameRate(30)
            recorder.setVideoSize(size.width, size.height)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (cam.withAudio) {
                recorder.setAudioEncodingBitRate(128_000)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            }
            recorder.setOrientationHint(sensorOrientation)
            recorder.prepare()
        } catch (e: Exception) {
            try { recorder.release() } catch (_: Exception) {}
            throw e
        }
        return recorder
    }

    // ---------------------------------------------------------------------------------------------
    // Stop
    // ---------------------------------------------------------------------------------------------

    private fun stopCapture() {
        val handler = backgroundHandler
        if (handler == null) {
            stopSelf()
            return
        }
        handler.post {
            for (cam in cams) {
                try {
                    finalizeCam(cam)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not finalize ${cam.label} recording", e)
                }
            }
            cams.clear()
            stopSelf()
        }
    }

    /** Stops the recorder cleanly (so the MP4 is playable) and queues encryption. */
    private fun finalizeCam(cam: Cam) {
        releaseCam(cam)
        val raw = cam.file
        cam.file = null
        if (raw != null && raw.exists()) {
            EncryptionManager.encryptAndDeleteAsync(raw)
        }
    }

    /** Safe to call more than once. */
    private fun releaseCam(cam: Cam) {
        if (cam.released) return
        cam.released = true

        try { cam.session?.stopRepeating() } catch (_: Exception) {}
        try { cam.session?.abortCaptures() } catch (_: Exception) {}

        val rec = cam.recorder
        if (rec != null) {
            if (cam.recording) {
                try {
                    rec.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "${cam.label} recorder.stop failed", e)
                }
            }
            try { rec.reset() } catch (_: Exception) {}
            try { rec.release() } catch (_: Exception) {}
        }
        cam.recording = false
        cam.recorder = null

        try { cam.session?.close() } catch (_: Exception) {}
        try { cam.device?.close() } catch (_: Exception) {}
        cam.session = null
        cam.device = null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only reached with cameras still open if the system tears the service down early.
        for (cam in cams) {
            try { releaseCam(cam) } catch (_: Exception) {}
        }
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
