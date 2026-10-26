package com.archie.cryptobot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fast mode loop: runs the bot every 30 seconds while switched on. Android only allows this for an app that
 * shows an ongoing notification, and it keeps the phone's processor awake, so it uses more battery.
 */
class FastService : Service() {
    companion object {
        private const val CHANNEL = "fast"
        private const val NOTIFICATION_ID = 42
        const val INTERVAL_MS = 30_000L

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, FastService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, FastService::class.java))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Fast mode", NotificationManager.IMPORTANCE_LOW))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Starting…"),
            if (android.os.Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cryptobot:fast").apply { acquire() }
        }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                if (!BotStore.isEnabled(this@FastService) || BotStore.settings(this@FastService).mode != "fast") break
                val text = try {
                    Bot.runOnce(this@FastService, trade = true).action.reason
                } catch (e: NotPaperException) {
                    Bot.record(this@FastService, "Stopped: ${e.message}", notify = true)
                    BotStore.setEnabled(this@FastService, false)
                    break
                } catch (e: Exception) {
                    "Error: ${e.message}".also { BotStore.setStatus(this@FastService, it) }
                }
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
                delay(INTERVAL_MS)
            }
            stopSelf()
        }
        return START_STICKY
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher)
        .setContentTitle("Fast mode running (paper)")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
