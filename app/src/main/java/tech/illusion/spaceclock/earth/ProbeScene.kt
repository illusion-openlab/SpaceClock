package tech.illusion.spaceclock.earth

import android.util.Log
import androidx.annotation.MainThread
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3

private const val TAG = "ClockProbe"

/** 从 Earth.usdz 离线量出来的常量，见设计文档 §4.1。运行时会用 getVisualBounds 复核。 */
private const val MODEL_CZ = 0.141627f

/**
 * 加载地球模型、量包围盒、把球心居中到视口原点。
 *
 * Task 0 曾在这里挂三个诊断标记点（判定 USD 加载器是否把 Z-up 转成了引擎的 Y-up），
 * 判定已有结论并写进 AGENTS.md，标记点本身在 Task 1 起移除。地球加载 + 居中逻辑保留，
 * Task 4 会把本文件整个替换成真正的 `EarthScene` + `GeoMath`。
 *
 * 刻意不收 `content` 参数——它只需要 [root]。把 [root] 挂进场景（`content.addEntity(root)`）
 * 是调用方的事，见 `EarthView`。
 *
 * 返回加载出来的地球实体，交给 [ProbeHandle] 保管，调用方必须把返回值交给
 * [ProbeHandle.attach]，否则就没有清理路径。
 */
suspend fun buildProbe(root: Entity): Entity {
    val earth = Entity.loadSuspend("asset://models/Earth.usdz")
    root.addChild(earth)

    // 加载器可能用两种方式处理 upAxis=Z：(a) 给根实体一个 -90°/X 的 xform，子节点跟着转，
    // §4.2 的公式在 earth 局部空间里原样成立；(b) 把旋转烘进顶点，实体 transform 是单位量，
    // 这时公式算出来的点会和网格错开 90°。所以先把实体自己的 transform 打出来。
    val loaded = earth.components[TransformComponent::class.java]
    Log.i(
        TAG,
        "earth transform pos=%s quat=%s scale=%s".format(
            loaded?.position?.toString(),
            loaded?.quaternion?.toString(),
            loaded?.scaleVector?.toString(),
        ),
    )

    val b = earth.getVisualBounds(relativeTo = root, recursive = true)
    Log.i(
        TAG,
        "earth bounds size=(%.4f,%.4f,%.4f) center=(%.4f,%.4f,%.4f)".format(
            b.size.x, b.size.y, b.size.z, b.center.x, b.center.y, b.center.z,
        ),
    )
    // 判定 1（数值，不依赖截图）：模型球心离原点 0.1416m，这个偏移出现在引擎的哪根轴上，
    // 就说明模型的 Z 极轴被映射到了哪根轴。
    Log.i(
        TAG,
        "AXIS-READOUT boundsCenter offset lands on: %s (expect ~%.4f on the polar axis)".format(
            dominantAxisOf(b.center), MODEL_CZ,
        ),
    )

    // 把球心移到视口原点。模型是「坐在地面上」的（球心在 +0.1416 的极轴方向上），不居中的话
    // 半个球会被 SpatialView 的裁剪盒切掉。用实测的 boundsCenter 而不是写死 MODEL_CZ，
    // 这样两种轴向约定下都成立。
    loaded?.apply {
        val before = position
        position = Vector3(before.x - b.center.x, before.y - b.center.y, before.z - b.center.z)
        Log.i(TAG, "earth recentred from %s to %s".format(before.toString(), position.toString()))
    }

    return earth
}

/**
 * 探针 3D 内容的生命周期句柄：只负责把清理时要用到的东西攥在手里。
 *
 * 为什么需要它：`SpatialView` 的 `initial` 是**异步的 suspend lambda**，而 Compose 的
 * `onDispose` 是**同步的、跑在主线程上**。两边要动同一批实体，就得有个双方都拿得到的容器。
 * 靠遍历场景去反查实体是不可靠的（别的内容也挂在同一个 view 上），所以这里显式记住。
 *
 * Task 4 的 `EarthScene` 沿用同一形状：`initial` 里 [attach]，`onDispose` 里 [dispose]。
 */
class ProbeHandle {

    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var earth: Entity? = null
    private var disposed = false

    /**
     * 记下清理所需的三个引用。
     *
     * 如果 [dispose] 已经先跑过了（窗口在 `initial` 还挂在 `loadSuspend` 上时就被关掉），
     * 这里立刻把刚建好的内容销毁掉，否则这批实体就成了没人认领的泄漏。
     */
    @MainThread
    fun attach(content: SpatialViewContent, root: Entity, earth: Entity) {
        if (disposed) {
            Log.i(TAG, "probe attach after dispose; tearing down immediately")
            teardown(content, root, earth)
            return
        }
        this.content = content
        this.root = root
        this.earth = earth
    }

    /**
     * 把建出来的地球实体连同它持有的网格/材质资源一起放掉，并从 `SpatialViewContent`
     * 里摘掉 [root]。幂等，重复调用是空操作。
     *
     * 先 `removeEntity` 再 `destroy`，避免 view 的实体集合里留下已销毁的引用；
     * `recursively = true` 是为将来子节点（Task 4 的城市标记点）留的余量。
     */
    @MainThread
    fun dispose() {
        if (disposed) return
        disposed = true
        val content = this.content
        val root = this.root
        val earth = this.earth
        this.content = null
        this.root = null
        this.earth = null
        if (content == null || root == null) {
            // initial 还没跑完（或者抛了），没有东西要清。
            Log.i(TAG, "probe dispose: nothing attached")
            return
        }
        teardown(content, root, earth)
    }

    private fun teardown(content: SpatialViewContent, root: Entity, earth: Entity?) {
        content.removeEntity(root)
        // 先销毁 earth（recursively=true 一并带走将来挂在它下面的子节点），再销毁 root，
        // 这样即使 buildProbe 中途失败、earth 为 null，root 也一定被放掉。
        val earthOk = earth?.destroy(recursively = true)
        val rootOk = root.destroy(recursively = true)
        Log.i(TAG, "probe disposed: earth.destroy=$earthOk root.destroy=$rootOk")
    }
}

/** 哪根轴上的分量最大，用于把 boundsCenter 位置读成一句人能判的话。 */
private fun dominantAxisOf(v: Vector3): String {
    val ax = kotlin.math.abs(v.x)
    val ay = kotlin.math.abs(v.y)
    val az = kotlin.math.abs(v.z)
    return when {
        ax >= ay && ax >= az -> if (v.x >= 0) "+X" else "-X"
        ay >= az -> if (v.y >= 0) "+Y" else "-Y"
        else -> if (v.z >= 0) "+Z" else "-Z"
    }
}
