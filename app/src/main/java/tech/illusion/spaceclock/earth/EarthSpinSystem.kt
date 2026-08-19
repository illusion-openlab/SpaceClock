package tech.illusion.spaceclock.earth

import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System

/**
 * 地球自转 / 对正插值的每帧驱动。
 *
 * 一个 System 服务整个应用：`System.register` 是 app-global 的（core-6.0.0 System.kt:103 按
 * class 去重），每个容器注册一次会导致每帧被 tick 多次。所以这里不持有任何状态，
 * 只把 tick 转给当前场景——场景实例由 [EarthScene.active] 这个静态句柄提供。
 */
class EarthSpinSystem : System() {
    override fun update(context: SceneUpdateContext) {
        EarthScene.active?.advance(context.deltaTime)
    }
}
