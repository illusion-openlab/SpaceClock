package tech.illusion.spaceclock.timing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "ProbeService"
private const val CHANNEL_ID = "clock_probe"
private const val NOTIFICATION_ID = 1001
private const val TICK_MS = 5_000L

/**
 * 平台探针：验证 `foregroundServiceType="specialUse"` 的前台服务在 PICO OS 6 上是否真的能跑。
 *
 * 只做两件事：`startForeground` 一条常驻通知，然后每 5 秒打一行日志。
 * 判定看两点——`startForeground` 有没有抛异常（targetSdk 35 下缺 `foregroundServiceType`
 * 会抛 `MissingForegroundServiceTypeException`），以及 tick 日志是否持续。
 *
 * Task 8 会用这个结论决定计时走前台服务还是降级为进程内计时。本文件到那时会被真正的
 * 计时服务替换。
 */
class ProbeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val ok = try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "FGS-READOUT startForeground OK type=specialUse")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "FGS-READOUT startForeground FAILED ${t.javaClass.name}: ${t.message}", t)
            false
        }
        if (!ok) {
            stopSelf()
            return
        }
        scope.launch {
            var tick = 0
            while (isActive) {
                Log.i(TAG, "FGS-READOUT alive tick=$tick elapsedRealtime=${SystemClock.elapsedRealtime()}")
                tick++
                delay(TICK_MS)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 生命周期清理：协程随服务一起结束，否则 tick 会在服务停掉之后继续打日志。
        scope.cancel()
        Log.i(TAG, "FGS-READOUT onDestroy")
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Clock probe", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("SpaceClock probe")
            .setContentText("Foreground service probe running")
            .setOngoing(true)
            .build()
}
