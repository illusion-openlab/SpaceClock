package tech.illusion.spaceclock.earth

import android.util.Log
import androidx.annotation.MainThread
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.OpacityControllerComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import tech.illusion.spaceclock.theme.PIN_COLOR_ARGB
import tech.illusion.spaceclock.time.City

private const val TAG = "EarthScene"

/** 地球模型资产。故意留成常量：故障注入验收（Task 4 Step 8）靠改这一行触发异常路径。 */
private const val EARTH_ASSET = "asset://models/Earth.usdz"

/** 地球目标直径。设计文档 §2 深度三层表。 */
private const val TARGET_DIAMETER_M = 0.20f

/**
 * 球心在 root 空间的 X 偏移（体积左侧）。
 *
 * 余量核算：体积窗口 0.60(W) × 0.45(H) × 0.30(D)，原点在几何中心，X 可用范围 ±0.30。
 * 球半径 0.10 + 标记点抬升 2% ≈ 0.102，因此 |LEFT_OFFSET_M| ≤ 0.30 − 0.102 = 0.198。
 * 取 −0.17，留约 2.8cm 余量（窗口可被用户缩放，别顶着边界放）。
 * Y/Z 方向球心在 0，余量分别是 0.225−0.102 和 0.15−0.102，都够。
 */
private const val LEFT_OFFSET_M = -0.17f

/** 标记点直径约 1.6cm（0.20m 的球上刚好可见又不糊成一片）——指 **root 空间**的成品尺寸。 */
private const val PIN_RADIUS_M = 0.004f

/**
 * 标记点抬离球面的比例。20 段低模的面心比理想球面凹 R(1−cos9°) ≈ 半径的 1.2%，
 * 贴着球面放会有一半标记点沉进面里，所以抬 2%。
 */
private const val PIN_LIFT = 1.02f

/** 一圈 120 秒 = 3°/s。挂件要的是"几乎看不出在动，但看久了确实转了"。 */
private const val SPIN_DEG_PER_SEC = 3f

/** 选中城市转到正面的插值时长。 */
private const val FOCUS_SECONDS = 0.6f

/**
 * 观察者方向在**引擎方位角**系里的角度（`atan2(z, x)`，度）。
 *
 * 体积窗口的正面朝 +Z，观察者在 +Z 一侧，所以正对观察者的球面点方位角是 +90°。
 * 这一条不是假设：Task 0 的截图实测 spin=0 时正对相机的半球中心是 realLon 72°E
 * （印度半岛居中），modelLon = 72+198 = 270°，而 spin=0 时方位角 λ 的点落在引擎方位角 −λ，
 * −270° 归一化正是 +90°。
 */
private const val VIEWER_AZIMUTH_DEG = 90f

/**
 * 自转角零点相对城市方位角的偏置，见 [focusTargetSpinDeg]。
 *
 * 推导（与 Task 4 Step 8 的设备实测一致，两轮证据见 task-4-report.md）：
 * 城市方位角 λ 的球面点在 spin=0 时落在引擎方位角 −λ，自转 s 度（绕引擎 +Y 的 yaw，
 * 右手系 +Z→+X）后落在 −λ−s。要它对准观察者（+90°）就得 s = −90 − λ，
 * 即 `FRONT_AZIMUTH_DEG = −VIEWER_AZIMUTH_DEG = −90`（等价 270）。
 */
private const val FRONT_AZIMUTH_DEG = -VIEWER_AZIMUTH_DEG

/**
 * 地球 3D 场景：模型 + 城市标记点 + 自转 / 选中对正。
 *
 * 昼夜暗罩（原 Task 4 Step 6）**降级到 V2**，上机实测的三条理由见 `AGENTS.md`
 * 「昼夜暗罩降级结论」与 task-4-report.md。
 *
 * 生命周期形状：`SpatialView` 的 `initial` 是**异步 suspend**，Compose 的 `onDispose` 是
 * **同步主线程**，两边要动同一批实体，所以场景对象自己攥住 `content` / `root` / `earth`，
 * 而不是让调用方分开保管——Task 0 探针的 `ProbeHandle` 就是因为「只在 build 正常返回后才登记」
 * 而漏掉了异常路径（Task 0 review 的延后 Minor）。这里 [build] 一创建就登记，
 * 并覆盖三条清理路径：正常、`dispose()` 撞上 [build] 唯一的挂起点（`loadSuspend`）、[build]
 * 中途抛异常——`loadSuspend` 恢复后立即检查 [disposed] 并拆除，不是等 [build] 收尾才发现。
 */
class EarthScene {

    companion object {
        /** System 是 app-global 的，靠这个静态句柄找到当前场景。dispose 时必须清空，否则泄漏。 */
        @Volatile
        var active: EarthScene? = null
    }

    private var content: SpatialViewContent? = null
    private var root: Entity? = null
    private var earth: Entity? = null

    /** 城市和它的标记点实体一起存：落点日志要能把 [City] 映射回实体。 */
    private val pins = mutableListOf<Pin>()

    /**
     * [dispose] 已经跑过。[build] 只有一个挂起点（`loadSuspend`），恢复后立即据此拆除；
     * [build] 收尾处还留了一次兜底检查，覆盖挂起点之后、方法返回之前的其余同步代码。
     */
    @Volatile
    private var disposed = false

    /** 球心在 earth 局部空间的位置（[build] 现场量），标记点定位要用。 */
    private var localCenter: Vector3 = Vector3(0f, 0f, 0f)

    /** 球半径，earth 局部单位（未乘 scale）。 */
    private var localRadius: Float = 0f

    /** earth 实体的等比缩放系数。标记点是子节点，要按它反算网格半径才能得到目标成品尺寸。 */
    private var earthScale: Float = 1f

    private var spinDeg: Float = 0f
    private var focusTargetDeg: Float? = null
    private var focusFromDeg: Float = 0f
    private var focusProgress: Float = 0f

    /** 正在对正的城市，只用于对正完成时打一行落点实测日志。 */
    private var focusCity: City? = null

    /** 对正刚刚完成，下一帧开头补一行落点实测（见 [advance] 里的说明）。 */
    private var landingLogPending = false

    /** 上一帧真正写进变换的自转角，落点日志要拿它对账。 */
    private var appliedSpinDeg: Float = 0f

    /**
     * 加载地球、现场量包围盒、缩放到目标直径、把**球心**摆到体积左侧。
     *
     * 由调用方在 `SpatialView(initial = ...)` 里调用一次。`root` 由本方法自己创建并挂进
     * [content]：这样「已挂进场景但还没人认领」的窗口宽度是零，异常路径也一定有人清理。
     */
    suspend fun build(content: SpatialViewContent) {
        active = this
        this.content = content
        try {
            val root = Entity()
            content.addEntity(root)
            this.root = root

            val earth = Entity.loadSuspend(EARTH_ASSET)
            this.earth = earth
            if (disposed) {
                // dispose() 在 loadSuspend 挂起期间到达：teardown() 已经把 root/earth 摘掉销毁、
                // 字段清空。继续往下走会对着已销毁的实体调 addChild / getVisualBounds / 写变换，
                // 所以在这唯一的挂起点恢复后立即检查并退出——不要等到方法收尾才发现。
                Log.i(TAG, "dispose arrived during load; tearing down immediately")
                teardown()
                return
            }
            root.addChild(earth)

            // 必须在施加任何变换**之前**测量：此刻实体 transform 是单位矩阵（Task 0 实测），
            // 所以 relativeTo = root 量出来的 center/size 同时就是 earth 的**局部**空间的量。
            // 施加 scale/position 之后再测就拿不到局部量了。
            val raw = earth.getVisualBounds(relativeTo = root, recursive = true)
            val widest = maxOf(raw.size.x, raw.size.y, raw.size.z)
            require(widest > 1e-5f) { "earth has no measurable bounds" }

            // 局部空间的球心与半径。全部现场量，不写死常量：换成兜底自建球（球心就在原点）时
            // 这两个值自动变成 (0,0,0) 和 0.10，代码不用改。
            localCenter = raw.center
            localRadius = widest / 2f

            val scale = TARGET_DIAMETER_M / widest
            earthScale = scale

            earth.components[TransformComponent::class.java]?.apply {
                scaleVector = Vector3(scale, scale, scale)
                // 居左，并抵掉局部球心偏移，使**球心**（而不是实体原点）落在 (LEFT_OFFSET_M, 0, 0)。
                position = Vector3(
                    LEFT_OFFSET_M - localCenter.x * scale,
                    -localCenter.y * scale,
                    -localCenter.z * scale,
                )
            }
            Log.i(
                TAG,
                "EARTH-READOUT size=(%.4f,%.4f,%.4f) localCenter=(%.4f,%.4f,%.4f) scale=%.5f radius=%.4f".format(
                    raw.size.x, raw.size.y, raw.size.z,
                    localCenter.x, localCenter.y, localCenter.z, scale, localRadius * scale,
                ),
            )
        } catch (t: Throwable) {
            // initial 是 suspend lambda，异常会被吞掉；这里必须自己清理，否则已经 addEntity 的
            // root 和可能已加载的 12.8MB 模型谁都不认领——泄漏且无日志（Task 0 探针的缺口）。
            Log.e(TAG, "earth scene build failed; tearing down", t)
            dispose()
            throw t
        }
        if (disposed) {
            // 兜底守卫：真正的竞态窗口（dispose() 撞上 loadSuspend 挂起）已经在挂起点恢复后
            // 立即处理并 return 了，不会走到这里。这里补的是防御性的第二层——万一以后有人在
            // try 块里再加一个挂起点，或者 disposed 的赋值/读取时序有别的路径，也不会漏拆。
            Log.i(TAG, "build finished after dispose; tearing down immediately")
            teardown()
        }
    }

    /**
     * 建 / 重建城市标记点。幂等：重复调用先销毁旧的，否则城市列表每增删一次就留一层残影。
     * `build` 之前调用是空操作（[earth] 还不存在）。
     */
    @MainThread
    fun setPins(cities: List<City>) {
        val earth = this.earth ?: return
        pins.forEach { it.entity.destroy() }
        pins.clear()

        val pinColor = Color4(
            ((PIN_COLOR_ARGB shr 16) and 0xFF) / 255f,
            ((PIN_COLOR_ARGB shr 8) and 0xFF) / 255f,
            (PIN_COLOR_ARGB and 0xFF) / 255f,
            1f,
        )
        // 标记点是 earth 的子节点，网格半径会被 earth 的 scale 一起缩，所以按 scale 反算，
        // 让 PIN_RADIUS_M 表达的是 root 空间的成品尺寸而不是随模型原始大小漂移的局部量。
        val mesh = MeshResource.createSphere(PIN_RADIUS_M / earthScale)
        cities.forEach { city ->
            val pin = Entity()
            pin.components.set(
                ModelComponent(
                    mesh,
                    // Unlit：不吃光照，所以即使 PBR 地球在这条管线上偏暗，标记点也一定看得见。
                    UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(pinColor) },
                ),
            )
            pin.components.set(OpacityControllerComponent(opacity = 0.6f))

            // 单位方向向量（模型坐标系）→ 引擎坐标系 → 乘局部半径 → **加上局部球心偏移**。
            //
            // 那个偏移是必须的：标记点挂在 earth 下，坐标系是 earth 的**局部**空间，而
            // Task 0 实测到球心在局部 (0, 0.1416, 0) 而不是原点。build 给实体设的 position
            // 移动的是整个实体，并不会把局部原点搬到球心上。漏掉它，全部标记点会整体偏移
            // 一个球半径的距离——而且看起来"像是差一点点"，很容易被误判成公式精度问题。
            // lift 只乘一次（PIN_LIFT），所以 surfacePoint 传 lift = 1f。
            val dir = toEngineTriple(GeoMath.surfacePoint(city.lat, city.lon, radius = 1f, lift = 1f))
            pin.components[TransformComponent::class.java]?.apply {
                position = Vector3(
                    localCenter.x + dir.first * localRadius * PIN_LIFT,
                    localCenter.y + dir.second * localRadius * PIN_LIFT,
                    localCenter.z + dir.third * localRadius * PIN_LIFT,
                )
            }
            // 挂在 earth 下而不是 root 下：自转时标记跟着转，不需要每帧重算坐标。
            earth.addChild(pin)
            pins += Pin(city, pin)
        }
        logPinPlacement()
    }

    /** 把某城转到面向观察者，600ms 插值。 */
    @MainThread
    fun focusOn(city: City) {
        focusFromDeg = spinDeg
        focusProgress = 0f
        focusCity = city
        val target = focusTargetSpinDeg(city.lon)
        focusTargetDeg = target
        Log.i(
            TAG,
            "FOCUS-READOUT city=%s lon=%.2f cityAzimuth=%.2f targetSpin=%.2f fromSpin=%.2f".format(
                city.name, city.lon, cityAzimuthDeg(city.lon), target, focusFromDeg,
            ),
        )
    }

    /**
     * 场景唯一的时间入口，由 [EarthSpinSystem] 每帧调用。
     *
     * 实测跑在主线程上（`TICK-DIAG ... thread=main`，dt≈0.0167s ≈ 60Hz），所以这里可以直接
     * 调 `@MainThread` 的实体 API。
     */
    @MainThread
    fun advance(dt: Float) {
        if (disposed) return
        val earth = this.earth ?: return
        // 落点日志要读上一帧已经生效的变换：同一次 advance 里 setQuaternion 之后立刻
        // getVisualBounds 拿到的还是上一帧的姿态（round 1 实测，见 task-4-report.md）。
        if (landingLogPending) {
            landingLogPending = false
            logFocusLanding()
        }
        val target = focusTargetDeg
        if (target != null) {
            // 插值也走这个 tick，不要再开第二个时间源。
            focusProgress = (focusProgress + dt / FOCUS_SECONDS).coerceAtMost(1f)
            val t = focusProgress * focusProgress * (3f - 2f * focusProgress) // smoothstep
            spinDeg = wrap360(focusFromDeg + shortestDelta(focusFromDeg, target) * t)
            if (focusProgress >= 1f) {
                focusTargetDeg = null
                landingLogPending = true
            }
        } else {
            spinDeg = wrap360(spinDeg + SPIN_DEG_PER_SEC * dt)
        }
        appliedSpinDeg = spinDeg
        earth.components[TransformComponent::class.java]?.setQuaternion(spinQuat(spinDeg))
    }

    /** 销毁全部实体、摘掉 root、清空 [active]。幂等。 */
    @MainThread
    fun dispose() {
        disposed = true
        if (active === this) active = null
        teardown()
    }

    /** 真正的拆除动作。可重复调用——竞态路径要拆两次（dispose 时一次，build 收尾一次）。 */
    @MainThread
    private fun teardown() {
        val had = root != null || earth != null || pins.isNotEmpty()
        pins.forEach { it.entity.destroy() }
        pins.clear()
        focusCity = null
        landingLogPending = false
        // recursively = true 一并带走挂在 earth 下的标记点（core-6.0.0 Entity.kt:567）。
        earth?.destroy(recursively = true)
        earth = null
        // 先 removeEntity 再 destroy，避免 view 的实体集合里留下已销毁的引用。
        root?.let { r ->
            content?.removeEntity(r)
            r.destroy(recursively = true)
        }
        root = null
        content = null
        Log.i(TAG, if (had) "earth scene torn down" else "earth scene teardown: nothing attached")
    }

    /**
     * 标记点落点实测：每个标记点在 root 空间的坐标，以及它到球心的距离。
     *
     * 距离是这一步的关键判定——把局部球心偏移漏掉的话，标记点会散布在离球心 0..0.20m 的
     * 各处，而不是全部落在 0.102m 的球面上。截图看不出这个差别，数字看得出。
     */
    private fun logPinPlacement() {
        val root = this.root ?: return
        pins.forEach { pin ->
            val c = pin.entity.getVisualBounds(relativeTo = root, recursive = true).center
            val dx = c.x - LEFT_OFFSET_M
            val dy = c.y
            val dz = c.z
            val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
            Log.i(
                TAG,
                "PIN-READOUT %s lat=%.2f lon=%.2f pos=(%.4f,%.4f,%.4f) distFromCentre=%.4f azimuth=%.1f spin=%.1f".format(
                    pin.city.name, pin.city.lat, pin.city.lon, c.x, c.y, c.z, dist,
                    Math.toDegrees(kotlin.math.atan2(dz.toDouble(), dx.toDouble())).toFloat(),
                    spinDeg,
                ),
            )
        }
    }

    /**
     * 对正完成时打一行落点：城市标记点到位后的引擎方位角应当等于 [VIEWER_AZIMUTH_DEG]。
     * 这是 [FRONT_AZIMUTH_DEG] 在设备上的判定依据，也是它当初的校准手段。
     */
    private fun logFocusLanding() {
        val city = focusCity ?: return
        val root = this.root ?: return
        focusCity = null
        val pin = pins.firstOrNull { it.city.name == city.name } ?: return
        val c = pin.entity.getVisualBounds(relativeTo = root, recursive = true).center
        val azimuth = Math.toDegrees(
            kotlin.math.atan2((c.z).toDouble(), (c.x - LEFT_OFFSET_M).toDouble()),
        ).toFloat()
        Log.i(
            TAG,
            "FOCUS-LANDED city=%s appliedSpin=%.2f pos=(%.4f,%.4f,%.4f) azimuth=%.1f (viewer=%.1f)".format(
                city.name, appliedSpinDeg, c.x, c.y, c.z, azimuth, VIEWER_AZIMUTH_DEG,
            ),
        )
    }

    private class Pin(val city: City, val entity: Entity)

    /** 绕极轴（引擎 Y）自转。`EulerAngles` 的 yaw 就是绕 Y，见 foundation-6.0.0 EulerAngles.kt。 */
    private fun spinQuat(deg: Float): Quat = EulerAngles(0f, deg, 0f).toQuat()
}

/**
 * 模型坐标（Z 为极轴）→ 引擎坐标（Y 为极轴）。
 * Task 0 实测：SDK 的 USD 加载器把 Z-up 转成 Y-up 并烘进顶点，手性未翻转。
 */
internal fun toEngineTriple(p: Triple<Float, Float, Float>): Triple<Float, Float, Float> =
    Triple(p.first, p.third, -p.second)

/** 城市在**模型方位角**系里的角度（就是 modelLon）。 */
internal fun cityAzimuthDeg(lonDeg: Float): Float = wrap360(lonDeg + GeoMath.LON_OFFSET_DEG)

/**
 * 把方位角为 [cityAzimuthDeg] 的球面点转到观察者正面所需的自转角。
 *
 * 城市方位角 λ 的点在 spin=0 时落在引擎方位角 −λ，自转 s（绕引擎 +Y 的 yaw）后落在 −λ−s，
 * 于是"到位"条件是 −λ−s = VIEWER_AZIMUTH_DEG，即 s = FRONT_AZIMUTH_DEG − λ。
 */
internal fun focusTargetSpinDeg(lonDeg: Float): Float =
    wrap360(FRONT_AZIMUTH_DEG - cityAzimuthDeg(lonDeg))

/** 取 ±180° 内的最短转向，否则从 350° 转到 10° 会绕一大圈。 */
internal fun shortestDelta(from: Float, to: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}

internal fun wrap360(deg: Float): Float {
    val d = deg % 360f
    return if (d < 0f) d + 360f else d
}
