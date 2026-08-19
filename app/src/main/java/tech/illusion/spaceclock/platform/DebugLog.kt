package tech.illusion.spaceclock.platform

import android.util.Log
import tech.illusion.spaceclock.BuildConfig

/**
 * 只在 debug 构建里打的诊断日志（Task 10 Step 1'）。
 *
 * 本工程有两类日志，判据不同：
 *
 * - **产品事件**（`ALERT-POSTED` / `PERSIST …` / `PHASE-BOUNDARY` / `startForeground OK` /
 *   `tap reached` 等）：低频、一次一件事，是线上排障与门禁 B 判定的依据，**不门控**。
 * - **周期性仪表**（`onGloballyPositioned` 坐标打点、秒表插值循环的 tick、服务的 TICK 心跳）：
 *   按布局 pass / 定时器频率触发。SpaceClock 是个**常驻挂件**——这类日志无门控地留着就是
 *   整个运行期每秒数行，既浪费也会把上面那类真正需要看的行淹掉。走这个函数。
 *
 * 用 `BuildConfig.DEBUG` 门控而不是整段删掉：门禁 B 装的就是 debug APK（`app-debug.apk`），
 * 取证能力一点没丢，而 release 包里这些行一条不打。`inline` + lambda 保证 release 下连字符串
 * 拼接都不会发生（`BuildConfig.DEBUG` 是编译期常量，整个调用会被优化掉）。
 */
internal inline fun debugLog(tag: String, message: () -> String) {
    if (BuildConfig.DEBUG) Log.i(tag, message())
}
