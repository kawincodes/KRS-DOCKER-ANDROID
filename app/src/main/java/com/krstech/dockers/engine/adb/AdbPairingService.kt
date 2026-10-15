package com.krstech.dockers.engine.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.krstech.dockers.DockersApp
import com.krstech.dockers.MainActivity
import com.krstech.dockers.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Shizuku-style pairing helper. While you have *Wireless debugging → Pair device with pairing code*
 * open in Settings, this service finds the pairing port over mDNS and posts a notification where
 * you type the 6-digit code without leaving Settings. After pairing it connects and starts the
 * shell service automatically.
 */
class AdbPairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adb get() = (application as DockersApp).container.adb

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForegroundCompat(searching())
                adb.startPairingDiscovery { port -> notify(askForCode(port)) }
            }
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()?.trim()
                val port = intent.getIntExtra(EXTRA_PORT, 0)
                if (code.isNullOrEmpty() || port == 0) return START_NOT_STICKY
                notify(progress("Pairing with code $code…"))
                scope.launch {
                    val paired = adb.pair(port, code)
                    if (paired.isFailure) { notify(askForCode(port, "Pairing failed. Try again", adb.status.value.message ?: "Check the code shown in Settings.")); return@launch }
                    notify(progress("Paired. Connecting…"))
                    val connected = adb.connect()
                    val svc = if (connected.isSuccess) adb.startService() else null
                    notify(
                        when {
                            svc?.isSuccess == true -> result("Shell service running", "KRS Dockers can now run privileged tasks without root.")
                            connected.isSuccess -> result("Connected over ADB", adb.status.value.message ?: "")
                            else -> result("Paired", "Open KRS Dockers → ADB & shell to connect.")
                        },
                    )
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf()
                }
            }
            ACTION_STOP -> { adb.stopPairingDiscovery(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() { adb.stopPairingDiscovery(); scope.cancel(); super.onDestroy() }

    // ------------------------------------------------------------------ notifications

    private fun base(): Notification.Builder {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ADB pairing", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentIntent(open).setOnlyAlertOnce(true)
    }

    private fun stopAction() = Notification.Action.Builder(null, "Cancel",
        PendingIntent.getService(this, 2, Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)).build()

    private fun searching() = base()
        .setContentTitle("Waiting for the pairing code dialog")
        .setContentText("Settings → Developer options → Wireless debugging → Pair device with pairing code")
        .setStyle(Notification.BigTextStyle().bigText("Open Settings → Developer options → Wireless debugging → \"Pair device with pairing code\". This notification will ask for the 6-digit code."))
        .setOngoing(true).addAction(stopAction()).build()

    private fun askForCode(port: Int, title: String = "Pairing service found", text: String = "Tap \"Enter pairing code\" and type the code shown in Settings."): Notification {
        val input = RemoteInput.Builder(KEY_CODE).setLabel("6-digit pairing code").build()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pi = PendingIntent.getService(this, 1, Intent(this, AdbPairingService::class.java).setAction(ACTION_CODE).putExtra(EXTRA_PORT, port), flags)
        val action = Notification.Action.Builder(null, "Enter pairing code", pi).addRemoteInput(input).build()
        return base().setContentTitle(title).setContentText(text)
            .setOngoing(true).addAction(action).addAction(stopAction()).build()
    }

    private fun progress(text: String) = base().setContentTitle("KRS Dockers ADB").setContentText(text).setProgress(0, 0, true).setOngoing(true).build()

    private fun result(title: String, text: String): Notification =
        base().setContentTitle(title).setContentText(text).setAutoCancel(true).build()

    private fun notify(n: Notification) = getSystemService(NotificationManager::class.java).notify(NOTIF_ID, n)

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(NOTIF_ID, n)
    }

    companion object {
        const val ACTION_START = "com.krstech.dockers.adb.PAIR_START"
        const val ACTION_CODE = "com.krstech.dockers.adb.PAIR_CODE"
        const val ACTION_STOP = "com.krstech.dockers.adb.PAIR_STOP"
        private const val EXTRA_PORT = "port"
        private const val KEY_CODE = "code"
        private const val CHANNEL = "adb_pairing"
        private const val NOTIF_ID = 1002
    }
}
