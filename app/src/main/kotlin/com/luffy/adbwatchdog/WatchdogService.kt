package com.luffy.adbwatchdog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class WatchdogService : LifecycleService() {

    private var pollJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIF_ID, buildNotification(R.string.notif_starting))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (pollJob?.isActive != true) {
            pollJob = lifecycleScope.launch { pollLoop() }
        }
        // 每次服务被拉起（包括被系统杀掉后的 START_STICKY 重启、复活链拉起），
        // 都把下一跳复活闹钟续上，形成自维持链条。
        ReviveReceiver.schedule(this)
        return START_STICKY
    }

    /**
     * 用户从最近任务里把 app 划掉时触发。此时进程可能很快被杀，
     * 立刻排一个 5 秒后的复活闹钟，让服务自己爬回来。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (prefs(this).getBoolean(KEY_ENABLED, false)) {
            ReviveReceiver.schedule(this, 5_000L)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        pollJob?.cancel()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        val prefs = prefs(this)
        while (true) {
            val intervalMs = prefs.getInt(KEY_INTERVAL_S, DEFAULT_INTERVAL_S).coerceAtLeast(2) * 1000L
            // 心跳：主界面据此判断服务是否还活着
            prefs.edit().putLong(KEY_HEARTBEAT, System.currentTimeMillis()).apply()
            val enabled = AdbWifiController.isEnabled(this)

            if (!enabled) {
                if (AdbWifiController.isWifiConnected(this)) {
                    Log.i(TAG, "Wireless ADB is OFF — attempting recovery")
                    updateNotification(R.string.notif_recovering)
                    val outcome = AdbWifiController.recover(this)
                    if (outcome != null) {
                        Log.i(TAG, "Recovered via $outcome")
                        incrementCounter(prefs, KEY_RECOVERIES)
                    } else {
                        Log.w(TAG, "Recovery failed")
                        incrementCounter(prefs, KEY_FAILURES)
                    }
                } else {
                    // No Wi-Fi: recovering is pointless (wireless debugging is bound
                    // to Wi-Fi). Pause until the network comes back; the next poll
                    // iteration will retry automatically.
                    Log.i(TAG, "Wireless ADB is OFF but Wi-Fi is disconnected — recovery paused")
                }
            }

            val stateRes = when {
                AdbWifiController.isEnabled(this) -> R.string.notif_up
                !AdbWifiController.isWifiConnected(this) -> R.string.notif_paused_no_wifi
                else -> R.string.notif_down
            }
            updateNotification(stateRes)
            delay(intervalMs)
        }
    }

    private fun incrementCounter(prefs: SharedPreferences, key: String) {
        prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    private fun updateNotification(stateRes: Int) {
        val nm = getSystemService(NotificationManager::class.java)
        // 没有通知权限时 notify 可能被拒，包一层避免任何意外
        runCatching { nm.notify(NOTIF_ID, buildNotification(stateRes)) }
    }

    private fun buildNotification(stateRes: Int): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(stateRes))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Android 12+：前台服务通知立即显示，不等待系统弹窗延迟
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                // DEFAULT（而非 LOW）：LOW 会被系统折叠进"静默通知"区域，
                // MagicOS 甚至会直接隐藏，这是之前"通知栏看不到状态"的主因。
                NotificationManager.IMPORTANCE_DEFAULT
            )
            ch.setSound(null, null)          // 常驻通知不响铃
            ch.enableVibration(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "AdbWatchdog"
        // v2：旧渠道以 LOW 重要级别创建过，改级别不会生效，因此换新 ID
        private const val CHANNEL_ID = "watchdog_v2"
        private const val NOTIF_ID = 1
        const val PREFS = "watchdog_prefs"
        const val KEY_ENABLED = "enabled"
        const val KEY_INTERVAL_S = "interval_s"
        const val KEY_RECOVERIES = "recoveries"
        const val KEY_FAILURES = "failures"
        const val KEY_LANG = "lang"
        const val KEY_HEARTBEAT = "last_heartbeat"
        const val KEY_LAST_REVIVE = "last_revive"
        const val DEFAULT_INTERVAL_S = 10

        fun prefs(ctx: Context): SharedPreferences =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun start(ctx: Context) {
            val intent = Intent(ctx, WatchdogService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(intent)
            } else {
                ctx.startService(intent)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WatchdogService::class.java))
        }
    }
}
