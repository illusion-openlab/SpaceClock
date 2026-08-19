package tech.illusion.spaceclock.platform

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import tech.illusion.spaceclock.store.ClockStore

/**
 * 申请 `POST_NOTIFICATIONS`（Android 13+ 的运行时权限，manifest 里声明拿不到），返回是否已授予。
 *
 * **被拒时功能不残缺**，这是设计要求不是可选项：
 * - 前台服务照常起——前台服务本身不要求通知权限，只是那条通知不会显示给用户；
 * - 闹钟到点仍然弹窗口内的 `AlarmCard`，倒计时到点仍然在页内显示「时间到」，通知只是补充；
 * - 被拒时到点告警发不出去，改由**补弹**兜底（`TimingService` 把未确认的提醒落盘，重开时再浮现）；
 * - 不因为没有通知权限就禁用任何功能。
 *
 * **只问一次（用户裁决①）**：标记落在 [ClockStore.notificationPermissionAsked] 里，跨进程持久。
 * 用 `LaunchedEffect(Unit)` 当"只问一次"的判据是错的——它只保证"每次挂载一次"，而重开窗口
 * 就会重挂载一次这棵树；Task 8 提交的 `task8-logcat.txt` 里同一个 pid（18113）出现过两条请求
 * 日志，正是这个原因。标记在拉起弹框**之前**就写盘：弹框期间进程被杀也不该变成再问一次。
 *
 * 在体积窗口内部用 `rememberLauncherForActivityResult` 是可行的，Task 8 已上机实证
 * （`task8-4-notif-denied.png` 里的系统弹框），不需要单独的 `LaunchActivity` 路由。
 */
@Composable
fun rememberNotificationPermission(store: ClockStore): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(RequestPermission()) { granted = it }
    LaunchedEffect(Unit) {
        if (!granted && !store.notificationPermissionAsked) {
            store.notificationPermissionAsked = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    return granted
}
