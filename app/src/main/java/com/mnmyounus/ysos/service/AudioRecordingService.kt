package com.mnmyounus.ysos.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import com.mnmyounus.ysos.security.EncryptionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures high-quality ambient audio in the background.
 * Encrypts the output file when stopped.
 */
class AudioRecordingService : Service() {

    private var recorder: MediaRecorder? = null
    private var rawFile: File? = null

    companion object {
        const val ACTION_START = "com.mnmyounus.ysos.action.AUDIO_START"
        const val ACTION_STOP = "com.mnmyounus.ysos.action.AUDIO_STOP"

        fun startRecording(context: Context) {
            context.startService(Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_START
            })
        }

        fun stopRecording(context: Context) {
            context.startService(Intent(context, AudioRecordingService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startAudioCapture()
            ACTION_STOP  -> stopAudioCapture()
        }
        return START_NOT_STICKY
    }

    private fun startAudioCapture() {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(filesDir, "evidence").apply { mkdirs() }
        rawFile = File(dir, "audio_$timestamp.3gp")

        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
            } else {
                @Suppress("DEPRECATION") MediaRecorder()
            }
            recorder!!.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(rawFile!!.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            recorder?.release()
            recorder = null
        }
    }

    private fun stopAudioCapture() {
        try {
            recorder?.stop()
        } catch (_: Exception) {}
        try {
            recorder?.release()
        } catch (_: Exception) {}
        recorder = null

        rawFile?.let { if (it.exists()) EncryptionManager.encryptAndDeleteAsync(it) }
        rawFile = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { recorder?.apply { stop(); release() } } catch (_: Exception) {}
        recorder = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
