package com.mnmyounus.ysos.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import com.mnmyounus.ysos.security.EncryptionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures the device screen via MediaProjection API.
 * Requires user to grant the screen-capture overlay permission before starting.
 */
class ScreenRecordingService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var rawFile: File? = null

    companion object {
        const val ACTION_START = "com.mnmyounus.ysos.action.SCREEN_START"
        const val ACTION_STOP  = "com.mnmyounus.ysos.action.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data_intent"

        fun startRecording(context: Context, captureIntent: Intent) {
            context.startService(
                Intent(context, ScreenRecordingService::class.java).apply {
                    action = ACTION_START
                    putExtra(EXTRA_RESULT_CODE, captureIntent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE))
                    putExtra(EXTRA_DATA, captureIntent)
                }
            )
        }

        fun stopRecording(context: Context) {
            context.startService(
                Intent(context, ScreenRecordingService::class.java).apply { action = ACTION_STOP }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val code = intent.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (code != Int.MIN_VALUE && data != null) startCapture(code, data)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        val projMgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projMgr.getMediaProjection(resultCode, data)

        val metrics = getDisplayMetrics()
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(filesDir, "evidence").apply { mkdirs() }
        rawFile = File(dir, "screen_$ts.mp4")

        try {
            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            mediaRecorder!!.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(w, h)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(5_000_000)
                setOutputFile(rawFile!!.absolutePath)
                prepare()
            }

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "YSOSScreen",
                w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder!!.surface, null, null
            )
            mediaRecorder?.start()
        } catch (e: Exception) {
            e.printStackTrace()
            cleanup()
        }
    }

    @Suppress("DEPRECATION")
    private fun getDisplayMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wb = wm.currentWindowMetrics.bounds
            metrics.widthPixels = wb.width()
            metrics.heightPixels = wb.height()
            metrics.densityDpi = resources.displayMetrics.densityDpi
        } else {
            wm.defaultDisplay.getMetrics(metrics)
        }
        return metrics
    }

    private fun stopCapture() {
        val file = rawFile
        cleanup()
        file?.let { if (it.exists()) EncryptionManager.encryptAndDelete(it) }
        rawFile = null
        stopSelf()
    }

    private fun cleanup() {
        try { mediaRecorder?.apply { stop(); release() } } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        mediaRecorder = null
        virtualDisplay = null
        mediaProjection = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
