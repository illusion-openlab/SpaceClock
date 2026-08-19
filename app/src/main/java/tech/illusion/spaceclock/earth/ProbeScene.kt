package tech.illusion.spaceclock.earth

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import kotlin.math.cos
import kotlin.math.sin

private const val TAG = "ClockProbe"

/** 从 Earth.usdz 离线量出来的常量，见设计文档 §4.1。运行时会用 getVisualBounds 复核。 */
private const val MODEL_R = 0.140328f
private const val MODEL_CZ = 0.141627f
private const val LON_OFFSET_DEG = 198f

/** 探针标记球半径。8mm 对 28cm 的地球约 2.8% 直径，在截图里仍是可辨认的一个点。 */
private const val PIN_RADIUS_M = 0.008f

/** 模型自身坐标系（Z 为极轴）下的表面点。设计文档 §4.2 的公式。 */
private fun modelSurface(latDeg: Float, realLonDeg: Float, lift: Float = 1.02f): Vector3 {
    val lat = Math.toRadians(latDeg.toDouble())
    val lon = Math.toRadians(((realLonDeg + LON_OFFSET_DEG) % 360f).toDouble())
    val r = MODEL_R * lift
    return Vector3(
        (r * cos(lat) * cos(lon)).toFloat(),
        (r * cos(lat) * sin(lon)).toFloat(),
        (MODEL_CZ + r * sin(lat)).toFloat(),
    )
}

/** 一个诊断标记点的规格：名字、纬度、真实经度、颜色。 */
private data class ProbePin(
    val name: String,
    val latDeg: Float,
    val lonDeg: Float,
    val color: Color4,
)

/**
 * 三个诊断标记点。它们的落点决定 SDK 的 USD 加载器是否把 Z-up 转成了引擎的 Y-up：
 *  - 北极点（红）：若落在球的**正上方** → 轴向被转换了（模型 Z 映射到引擎 Y）
 *                  若落在球的**正后/正前方** → 轴向原样保留
 *  - 北京（黄）、里约（绿）：确认经度方向与手性没有被镜像
 */
private val PROBE_PINS = listOf(
    ProbePin("northpole", 89.9f, 0f, Color4(1f, 0.2f, 0.2f, 1f)),
    ProbePin("beijing", 39.9f, 116.4f, Color4(1f, 0.9f, 0.4f, 1f)),
    ProbePin("rio", -22.9f, -43.2f, Color4(0.3f, 1f, 0.5f, 1f)),
)

/**
 * 建立平台风险探针场景：加载地球、量包围盒、放三个诊断标记点。
 *
 * 三件事都只在日志和一张截图里留证据，没有任何 UI。Task 4 会删掉本文件。
 *
 * 刻意不收 `content` 参数——它只需要 [root]。把 [root] 挂进场景（`content.addEntity(root)`）
 * 是调用方的事，见 `HomeVolume`。
 */
suspend fun buildProbe(root: Entity) {
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

    val pins = PROBE_PINS.map { spec ->
        val p = modelSurface(spec.latDeg, spec.lonDeg)
        val pin = Entity()
        pin.components.set(
            ModelComponent(
                MeshResource.createSphere(PIN_RADIUS_M),
                UnlitMaterial.create(BlendingMode.OPAQUE).apply { setBaseColor(spec.color) },
            )
        )
        pin.components[TransformComponent::class.java]?.apply { position = p }
        earth.addChild(pin) // 挂在地球下，自转时跟着转
        Log.i(TAG, "pin ${spec.name} local=(%.4f,%.4f,%.4f)".format(p.x, p.y, p.z))
        spec.name to pin
    }

    // 判定 2（数值）：标记点在引擎空间里实际落在哪。若北极点的偏移和 boundsCenter 落在同一
    // 根轴上，说明 (a)——公式与网格一致；落在不同轴上说明 (b)——GeoMath 需要自己做换算。
    pins.forEach { (name, pin) ->
        val pb = pin.getVisualBounds(relativeTo = root, recursive = true)
        Log.i(
            TAG,
            "PIN-READOUT %s engine=(%.4f,%.4f,%.4f) dominant=%s".format(
                name, pb.center.x, pb.center.y, pb.center.z, dominantAxisOf(pb.center),
            ),
        )
    }

    // 把球心移到视口原点。模型是「坐在地面上」的（球心在 +0.1416 的极轴方向上），不居中的话
    // 半个球连带北极标记点都会被 SpatialView 的裁剪盒切掉，截图上就判不出轴向了。
    // 用实测的 boundsCenter 而不是写死 MODEL_CZ，这样两种轴向约定下都成立。
    loaded?.apply {
        val before = position
        position = Vector3(before.x - b.center.x, before.y - b.center.y, before.z - b.center.z)
        Log.i(TAG, "earth recentred from %s to %s".format(before.toString(), position.toString()))
    }
}

/** 哪根轴上的分量最大，用于把 boundsCenter / 标记点位置读成一句人能判的话。 */
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
