package com.streamezy.capture.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.streamezy.capture.MainActivity
import com.streamezy.capture.R

/**
 * Foreground Service that holds high-performance CPU wake locks and Wi-Fi locks,
 * preventing Android OS Doze mode, thermal power-saving, and background network
 * teardown while multi-path bonding is streaming.
 */
class BondStreamingService : Service() {

    companion object {
        private const val TAG = "BondStreamingService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "streamezy_bonding_channel"
        private const val CHANNEL_NAME = "StreamEzy Live Bonding"

        const val ACTION_START = "com.streamezy.capture.service.START"
        const val ACTION_STOP = "com.streamezy.capture.service.STOP"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_BITRATE = "extra_bitrate"

        fun startService(context: Context, statusText: String = "Streaming Active") {
            val intent = Intent(context, BondStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, statusText)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun updateStatus(context: Context, statusText: String, bitrateMbps: Double) {
            val intent = Intent(context, BondStreamingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_STATUS, statusText)
                putExtra(EXTRA_BITRATE, bitrateMbps)
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, BondStreamingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): BondStreamingService = this@BondStreamingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        Log.i(TAG, "BondStreamingService created and wake locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping BondStreamingService...")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val statusText = intent?.getStringExtra(EXTRA_STATUS) ?: "Bonded Streaming Active"
        val bitrate = intent?.getDoubleExtra(EXTRA_BITRATE, 0.0) ?: 0.0
        val notification = buildNotification(statusText, bitrate)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set camera/mic foreground service type: ${e.message}")
                }
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreamEzy::BondStreamingLock")?.apply {
                setReferenceCounted(false)
                acquire(4 * 3600 * 1000L) // 4 hours maximum timeout
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            } else {
                WifiManager.WIFI_MODE_FULL
            }
            wifiLock = wm?.createWifiLock(mode, "StreamEzy::WifiHighPerfLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "High-performance CPU and Wi-Fi locks active")
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            Log.i(TAG, "Wake locks released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live streaming bonding connection status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String, bitrateMbps: Double): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        )

        val contentText = if (bitrateMbps > 0.0) {
            "$statusText | %.2f Mbps".format(bitrateMbps)
        } else {
            statusText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentTitle("StreamEzy Live Bonding")
            .setContentText(contentText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
        Log.i(TAG, "BondStreamingService destroyed")
    }
}
