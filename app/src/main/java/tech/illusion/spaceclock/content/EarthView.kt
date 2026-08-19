package tech.illusion.spaceclock.content

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pico.spatial.core.ecs.System
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.layout.depth
import com.pico.spatial.ui.platform.LengthUnit
import com.pico.spatial.ui.platform.LocalPhysicalLengthConverter
import tech.illusion.spaceclock.earth.EarthScene
import tech.illusion.spaceclock.earth.EarthSpinSystem
import tech.illusion.spaceclock.time.City

private const val TAG = "EarthScene"

/**
 * 地球 3D 背景层。
 *
 * [cities] 决定标记点，[focusCity] 非空时把该城转到正面。两者都由 `ClockVolume` 持有——
 * 标记点必须在切页后仍然存在，而页面级 `remember` 会随页面重组销毁。
 *
 * 时序陷阱：`SpatialView` 的 `initial` 是 **suspend lambda，跑得比 `LaunchedEffect` 晚**。
 * 所以场景内容不在 `initial` 里直接写、再由 effect 覆写一遍——`initial` 只负责建场景并翻
 * [built] 这个开关，标记点和对正统一由带 `built` key 的 effect 施加，全程只有一处写入点。
 */
@Composable
fun EarthView(
    cities: List<City>,
    focusCity: City?,
    modifier: Modifier = Modifier,
) {
    val converter = LocalPhysicalLengthConverter.current

    // 建出来的 3D 内容（12.8MB 模型 + 网格/材质/标记点）要有主人，否则窗口关掉之后没人放。
    // `initial` 是异步的、`onDispose` 是同步主线程的，场景对象是两边唯一的交汇点。
    val scene = remember { EarthScene() }
    var built by remember { mutableStateOf(false) }
    DisposableEffect(scene) {
        onDispose { scene.dispose() }
    }

    LaunchedEffect(scene, built, cities) {
        if (built) scene.setPins(cities)
    }
    LaunchedEffect(scene, built, focusCity) {
        if (built && focusCity != null) scene.focusOn(focusCity)
    }

    SpatialView(
        modifier = modifier.depth(converter.lengthToDp(VIEW_DEPTH_M, LengthUnit.Meters)),
        initial = { content, _ ->
            // initial 是 suspend lambda，异常会被吞掉——症状是"场景建到一半就停了"且毫无线索。
            try {
                // System.register 是 app-global 且按 class 去重的，重复调用安全。
                System.register(EarthSpinSystem::class.java)
                scene.build(content)
                built = true
            } catch (t: Throwable) {
                Log.e(TAG, "earth scene failed", t)
            }
        },
    )
}

/**
 * SpatialView 要求的深度。实体是被裁剪到 **view 的** 包围盒（不是 WindowContainer 的），所以
 * 必须显式把 view 撑到容器的深度；宽高由 `fillMaxSize` 从容器测得，深度测不回来。
 * 地球缩到 0.20m 直径后 0.30m 深度余量充足（`EarthScene` 的 LEFT_OFFSET_M 注释有余量核算）。
 */
private const val VIEW_DEPTH_M = 0.30f
