package tech.illusion.spaceclock.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import tech.illusion.spaceclock.earth.ProbeHandle
import tech.illusion.spaceclock.earth.buildProbe

private const val TAG = "ClockProbe"

/**
 * 地球的 `SpatialView` 空壳：本任务只负责把 Task 0 的加载代码接进新的三层布局背景层，
 * 不做任何交互或真实场景内容——那是 Task 4 的 `EarthScene`。
 *
 * 沿用 Task 0 `HomeVolume` 里验证过的生命周期形状：`initial`（异步 suspend）里
 * `attach(content, root, earth)`，`onDispose`（同步、主线程）里 `dispose()`。
 */
@Composable
fun EarthView(modifier: Modifier = Modifier) {
    val converter = LocalPhysicalLengthConverter.current

    // 探针建出来的 3D 内容（地球 + 网格/材质）要有主人，否则窗口关掉之后这批实体和 12.8MB
    // 的模型资源没人放。`initial` 是异步的，`onDispose` 是同步的，句柄是两边唯一的交汇点。
    val probe = remember { ProbeHandle() }
    DisposableEffect(probe) {
        onDispose { probe.dispose() }
    }

    SpatialView(
        modifier = modifier.depth(converter.lengthToDp(VIEW_DEPTH_M, LengthUnit.Meters)),
        initial = { content, _ ->
            // initial 是 suspend lambda，异常会被吞掉——症状是"场景建到一半就停了"且毫无线索。
            try {
                val root = Entity()
                content.addEntity(root)
                val earth = buildProbe(root)
                probe.attach(content, root, earth)
            } catch (t: Throwable) {
                Log.e(TAG, "probe failed", t)
            }
        },
    )
}

/**
 * SpatialView 要求的深度。实体是被裁剪到 **view 的** 包围盒（不是 WindowContainer 的），所以
 * 必须显式把 view 撑到容器的深度；宽高由 `fillMaxSize` 从容器测得，深度测不回来。
 * 地球直径 0.2807m（设计文档 §4.1），0.30m 刚好容得下。
 */
private const val VIEW_DEPTH_M = 0.30f
