package com.mnmyounus.ysos.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.view.Surface
import com.mnmyounus.ysos.security.EncryptionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records video from front and back camera concurrently using Camera2 API.
 * On devices that don't support concurrent camera streams the front camera
 * is skipped gracefully without crashing.
 */
class CameraRecordingService : Service() {

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var backCameraDevice: CameraDevice? = null
    private var backRecorder: MediaRecorder? = null
    private var backSession: CameraCaptureSession? = null
    private var backRawFile: File? = null

    private var frontCameraDevice: CameraDevice? = null
    private var frontRecorder: MediaRecorder? = null
    private var frontSession: CameraCaptureSession? = null
    private var frontRawFile: File? = null

    companion object {
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
        startBgThread()
    }

    private fun startBgThread() {
        backgroundThread = HandlerThread("CameraThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
    }

    private fun stopBgThread() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) {}
        backgroundThread = null
        backgroundHandler = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCapture()
            ACTION_STOP  -> stopCapture()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startCapture() {
        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val dir = File(filesDir, "evidence").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        try {
            val ids = mgr.cameraIdList
            val backId = ids.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val frontId = ids.firstOrNull { id ->
                mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }

            backId?.let { id ->
                backRawFile = File(dir, "cam_back_$ts.mp4")
                backRecorder = buildRecorder(backRawFile!!)
                openCamera(mgr, id, isBack = true)
            }

            // Slight delay prevents resource conflict on older devices
            backgroundHandler?.postDelayed({
                frontId?.let { id ->
                    try {
                        frontRawFile = File(dir, "cam_front_$ts.mp4")
                        frontRecorder = buildRecorder(frontRawFile!!)
                        openCamera(mgr, id, isBack = false)
                    } catch (e: CameraAccessException) {
                        e.printStackTrace() // Front camera not available concurrently — acceptable
                    }
                }
            }, 600L)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(mgr: CameraManager, id: String, isBack: Boolean) {
        mgr.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (isBack) backCameraDevice = camera else frontCameraDevice = camera
                val rec = if (isBack) backRecorder else frontRecorder
                startSession(camera, rec!!, isBack)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close() }
            override fun onError(camera: CameraDevice, error: Int) { camera.close() }
        }, backgroundHandler)
    }

    private fun startSession(camera: CameraDevice, recorder: MediaRecorder, isBack: Boolean) {
        try {
            val tex = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
            val preview = Surface(tex)
            val recSurface = recorder.surface

            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(recSurface)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(
                listOf(preview, recSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (isBack) backSession = session else frontSession = session
                        session.setRepeatingRequest(request.build(), null, backgroundHandler)
                        recorder.start()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildRecorder(outputFile: File): MediaRecorder {
        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setVideoSize(1280, 720)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(3_000_000)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(outputFile.absolutePath)
            prepare()
        }
    }

    private fun stopCapture() {
        releaseCamera(backSession, backCameraDevice, backRecorder, backRawFile)
        releaseCamera(frontSession, frontCameraDevice, frontRecorder, frontRawFile)
        backSession = null; backCameraDevice = null; backRecorder = null; backRawFile = null
        frontSession = null; frontCameraDevice = null; frontRecorder = null; frontRawFile = null
        stopSelf()
    }

    private fun releaseCamera(
        session: CameraCaptureSession?,
        device: CameraDevice?,
        recorder: MediaRecorder?,
        rawFile: File?
    ) {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
        rawFile?.let { if (it.exists()) EncryptionManager.encryptAndDelete(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { backSession?.close(); backCameraDevice?.close(); backRecorder?.apply { stop(); release() } } catch (_: Exception) {}
        try { frontSession?.close(); frontCameraDevice?.close(); frontRecorder?.apply { stop(); release() } } catch (_: Exception) {}
        stopBgThread()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
