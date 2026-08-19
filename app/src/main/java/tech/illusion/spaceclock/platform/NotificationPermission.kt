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

/**
 * 申请 `POST_NOTIFICATIONS`（Android 13+ 的运行时权限，manifest 里声明拿不到），返回是否已授予。
 *
 * **被拒时功能不残缺**，这是设计要求不是可选项：
 * - 前台服务照常起——前台服务本身不要求通知权限，只是那条通知不会显示给用户；
 * - 闹钟到点仍然弹窗口内的 `AlarmCard`，那是主路径，通知只是补充；
 * - 不因为没有通知权限就禁用任何功能。
 *
 * **不反复弹框**：`LaunchedEffect(Unit)` 只在进入这棵组合树时请求一次，被拒后本次运行不再请求
 * （系统本身在用户拒绝两次后也会直接返回拒绝，反复弹只会骚扰用户）。
 *
 * 在体积窗口内部用 `rememberLauncherForActivityResult` 是可行的：`SpatialLaunchActivity` 是
 * `ComponentActivity`，`LocalActivityResultRegistryOwner` 不为空（工作区内 SpaceVinyl 的
 * `HomeVolume` 已在生产代码里走通同一条路径）。
 */
@Composable
fun rememberNotificationPermission(): Boolean {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    return granted
}
