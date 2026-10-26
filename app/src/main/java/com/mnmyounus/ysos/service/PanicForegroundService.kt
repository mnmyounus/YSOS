package com.mnmyounus.ysos.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mnmyounus.ysos.MainActivity
import com.mnmyounus.ysos.R

/**
 * Orchestrating foreground service that starts/stops all recording sub-services
 * and holds a WakeLock to keep the CPU awake during evidence capture.
 */
class PanicForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "ysos_panic_recording"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.mnmyounus.ysos.action.PANIC_START"
        const val ACTION_STOP = "com.mnmyounus.ysos.action.PANIC_STOP"
        const val EXTRA_SCREEN_CAPTURE_INTENT = "screen_capture_intent"

        @Volatile
        var isRunning = false
            private set

        fun startRecording(context: Context, screenCaptureIntent: Intent? = null) {
            val intent = Intent(context, PanicForegroundService::class.java).apply {
                action = ACTION_START
                screenCaptureIntent?.let { putExtra(EXTRA_SCREEN_CAPTURE_INTENT, it) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            context.startService(
                Intent(context, PanicForegroundService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
                isRunning = true
                startAllRecordingServices(intent)
            }
            ACTION_STOP -> {
                stopAllRecordingServices()
                releaseWakeLock()
                isRunning = false
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startAllRecordingServices(intent: Intent) {
        AudioRecordingService.startRecording(this)
        CameraRecordingService.startRecording(this)

        @Suppress("DEPRECATION")
        val screenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT)
        }
        if (screenIntent != null) {
            ScreenRecordingService.startRecording(this, screenIntent)
        }
    }

    private fun stopAllRecordingServices() {
        AudioRecordingService.stopRecording(this)
        CameraRecordingService.stopRecording(this)
        ScreenRecordingService.stopRecording(this)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YSOS::PanicWakeLock")
            .apply { acquire(10 * 60 * 1000L) } // max 10 min
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val mainPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, PanicForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_panic_title))
            .setContentText(getString(R.string.notif_panic_text))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_stop_action), stopPi)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_panic),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_panic_desc)
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
        isRunning = false
    }
}
