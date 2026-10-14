package com.krstech.dockers.engine.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.krstech.dockers.MainActivity
import com.krstech.dockers.R
import java.io.File

/** Foreground service that keeps the root `dockerd` process alive while the engine runs. */
class EngineService : Service() {
    private var process: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat()
                val cmd = intent?.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                val log = intent?.getStringExtra(EXTRA_LOG)?.let { File(it) }
                runCatching { process?.destroy() }
                process = runCatching { RootShell.start(cmd, log) }.getOrNull()
                acquireWakeLock()
            }
            ACTION_STOP -> {
                runCatching { process?.destroy() }
                process = null
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.engine_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.engine_running))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop engine", stopIntent).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIF_ID, n)
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KRSDockers:engine").apply { setReferenceCounted(false) }
        }
        wakeLock?.takeUnless { it.isHeld }?.acquire()
    }

    private fun releaseWakeLock() { wakeLock?.takeIf { it.isHeld }?.release() }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.krstech.dockers.engine.START"
        const val ACTION_STOP = "com.krstech.dockers.engine.STOP"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_LOG = "log"
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 1001
    }
}
