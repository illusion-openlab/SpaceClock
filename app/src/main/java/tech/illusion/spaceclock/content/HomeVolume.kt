package tech.illusion.spaceclock.content

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import tech.illusion.spaceclock.earth.ProbeHandle
import tech.illusion.spaceclock.earth.buildProbe
import tech.illusion.spaceclock.timing.ProbeService

private const val TAG = "ClockProbe"

/**
 * Task 0 的探针页面。**不是**最终 UI——它只承载三个平台判定项（模型能否加载 / 坐标轴约定 /
 * 前台服务是否可用），Task 1 起会被真正的时钟界面替换。模板自带的 `box.usdz` 与
 * "Hello, Spatial SDK!" 面板已整体移除，不保留。
 */
@Composable
fun HomeVolume() {
    val converter = LocalPhysicalLengthConverter.current
    val context = LocalContext.current

    // 探针建出来的 3D 内容（地球 + 3 个标记点 + 它们的网格/材质）要有主人，否则窗口关掉之后
    // 这批实体和 12.8MB 的模型资源没人放。`initial` 是异步的，`onDispose` 是同步的，句柄是
    // 两边唯一的交汇点。
    val probe = remember { ProbeHandle() }
    DisposableEffect(probe) {
        onDispose { probe.dispose() }
    }

    // 前台服务探针。启动写在 Composable 里是因为它只活到探针结束——onDispose 必须停掉它，
    // 否则窗口关掉之后通知和 tick 日志会一直留着。
    DisposableEffect(context) {
        val intent = Intent(context, ProbeService::class.java)
        try {
            context.startForegroundService(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "FGS-READOUT startForegroundService threw ${t.javaClass.name}: ${t.message}", t)
        }
        onDispose { context.stopService(intent) }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .depth(converter.lengthToDp(VIEW_DEPTH_M, LengthUnit.Meters)),
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
 * 地球直径 0.2807m（设计文档 §4.1），0.30m 刚好容得下抬高 2% 后的标记点。
 */
private const val VIEW_DEPTH_M = 0.30f
