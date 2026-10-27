package com.mnmyounus.ysos.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mnmyounus.ysos.MainActivity
import com.mnmyounus.ysos.R
import com.mnmyounus.ysos.security.EncryptionManager

/**
 * Orchestrating foreground service that starts/stops all recording sub-services
 * and holds a WakeLock to keep the CPU awake during evidence capture.
 *
 * After Stop it stays alive (showing "saving") until the recordings are encrypted,
 * so Android does not kill the app halfway through saving evidence.
 */
class PanicForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "YSOSPanicService"
        private const val MAX_SAVE_WAIT_MS = 15 * 60 * 1000L

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
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> {
                // Restarted by the system without an intent: nothing to do, and we must not
                // linger as a foreground service that never called startForeground().
                if (!isRunning) stopSelf()
            }
        }
        // NOT_STICKY: a sticky restart delivers a null intent and Android then crashes the app
        // because startForeground() was never called.
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------------
    // Start / stop
    // ---------------------------------------------------------------------------------------------

    private fun handleStart(intent: Intent) {
        val screenIntent = readScreenIntent(intent)

        // Must be called every time startForegroundService() was used.
        if (!enterForeground(screenIntent != null)) {
            stopSelf()
            return
        }
        if (isRunning) return

        acquireWakeLock()
        isRunning = true
        startAllRecordingServices(screenIntent)
    }

    private fun handleStop() {
        if (!isRunning) {
            stopSelf()
            return
        }
        isRunning = false
        stopAllRecordingServices()
        showSavingNotification()

        // Keep the service (and wake lock) alive until encryption is finished.
        Thread {
            try {
                Thread.sleep(3000) // give the recorders time to hand over their files
                var waited = 0L
                while (EncryptionManager.hasPendingJobs() && waited < MAX_SAVE_WAIT_MS) {
                    Thread.sleep(500)
                    waited += 500
                }
            } catch (_: InterruptedException) {
            }
            Handler(Looper.getMainLooper()).post {
                // A new recording may have been started while we were saving.
                if (!isRunning) {
                    releaseWakeLock()
                    ServiceCompat.stopForeground(this@PanicForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }.start()
    }

    /**
     * Android 14+ only allows the foreground types we can really use right now:
     * camera/microphone need their permission, mediaProjection needs the user to have
     * approved screen capture. Declaring a type we cannot use throws SecurityException
     * (this was the cause of random crashes when screen capture was declined).
     */
    private fun enterForeground(withProjection: Boolean): Boolean {
        val notification = buildNotification()
        var types = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (hasPermission(Manifest.permission.CAMERA)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (hasPermission(Manifest.permission.RECORD_AUDIO)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (withProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        }
        return try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
            true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            false
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun readScreenIntent(intent: Intent): Intent? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_SCREEN_CAPTURE_INTENT)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not read screen capture intent", e)
            null
        }
    }

    private fun startAllRecordingServices(screenIntent: Intent?) {
        AudioRecordingService.startRecording(this)
        CameraRecordingService.startRecording(this)
        if (screenIntent != null) {
            ScreenRecordingService.startRecording(this, screenIntent)
        }
    }

    private fun stopAllRecordingServices() {
        AudioRecordingService.stopRecording(this)
        CameraRecordingService.stopRecording(this)
        ScreenRecordingService.stopRecording(this)
    }

    // ---------------------------------------------------------------------------------------------
    // Wake lock + notifications
    // ---------------------------------------------------------------------------------------------

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YSOS::PanicWakeLock")
            .apply { acquire(30 * 60 * 1000L) } // max 30 min safety net
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
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

    private fun showSavingNotification() {
        val mainPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_panic_title))
            .setContentText("Saving and encrypting evidence...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Could not update notification", e)
        }
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
