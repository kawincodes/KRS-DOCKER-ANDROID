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
import com.krstech.dockers.DockersApp
import com.krstech.dockers.MainActivity
import com.krstech.dockers.R
import java.io.File

/**
 * Foreground service that keeps Docker alive while the app is in the background. It owns the root
 * `dockerd` process (rooted devices) and keeps the process hosting the QEMU VM (no-root mode) at
 * foreground priority with a wake lock.
 */
class EngineService : Service() {
    private var rootProcess: Process? = null
    private var vmActive = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var text: String = "Docker engine is running"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                text = getString(R.string.engine_running)
                startForegroundCompat()
                val cmd = intent.getStringExtra(EXTRA_COMMAND) ?: return START_NOT_STICKY
                val log = intent.getStringExtra(EXTRA_LOG)?.let { File(it) }
                runCatching { rootProcess?.destroy() }
                rootProcess = runCatching { RootShell.start(cmd, log) }.getOrNull()
                acquireWakeLock()
            }
            ACTION_KEEPALIVE -> {
                vmActive = true
                text = intent.getStringExtra(EXTRA_TEXT) ?: "Docker VM running"
                startForegroundCompat()
                acquireWakeLock()
            }
            ACTION_RELEASE_VM -> {
                vmActive = false
                stopIfIdle()
            }
            ACTION_STOP -> {
                runCatching { rootProcess?.destroy() }
                rootProcess = null
                stopIfIdle()
            }
            ACTION_STOP_ALL -> {
                runCatching { rootProcess?.destroy() }
                rootProcess = null
                (application as? DockersApp)?.container?.vm?.stop()
                // The VM calls ACTION_RELEASE_VM once QEMU has exited.
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopIfIdle() {
        if (rootProcess == null && !vmActive) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.engine_notification_channel), NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pi)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop Docker", stopIntent).build())
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
        const val ACTION_STOP_ALL = "com.krstech.dockers.engine.STOP_ALL"
        const val ACTION_KEEPALIVE = "com.krstech.dockers.engine.KEEPALIVE"
        const val ACTION_RELEASE_VM = "com.krstech.dockers.engine.RELEASE_VM"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_LOG = "log"
        const val EXTRA_TEXT = "text"
        private const val CHANNEL = "engine"
        private const val NOTIF_ID = 1001
    }
}
