package tech.illusion.spaceclock.earth

import kotlin.math.cos
import kotlin.math.sin

/**
 * 经纬 → Earth.usdz 模型坐标系下的球面点（Z 为极轴）。
 *
 * 映射链路全部来自对模型文件的实测，见设计文档 §4.1/§4.2，不是假设：
 *   UV:  v = (lat+90)/180 , u = (modelLon-18)/360
 *   贴图: 标准等距圆柱, realLon = -180 + 360u
 *   合并: modelLon = (realLon + 198) mod 360
 *
 * 刻意不加球心偏移（模型球心在 z=0.141627，不在原点）：偏移和轴向换算取决于 SDK 加载器
 * 是否把 Z-up 转成 Y-up，那是 Task 0 的实测结论，属于调用方的知识。
 * 这里只做纯数学，才能单测。
 */
object GeoMath {
    const val LON_OFFSET_DEG = 198f

    fun surfacePoint(
        latDeg: Float,
        lonDeg: Float,
        radius: Float,
        lift: Float = 1.02f,
    ): Triple<Float, Float, Float> {
        val lat = Math.toRadians(latDeg.toDouble())
        var lonDegModel = (lonDeg + LON_OFFSET_DEG) % 360f
        if (lonDegModel < 0f) lonDegModel += 360f
        val lon = Math.toRadians(lonDegModel.toDouble())
        val r = radius * lift
        return Triple(
            (r * cos(lat) * cos(lon)).toFloat(),
            (r * cos(lat) * sin(lon)).toFloat(),
            (r * sin(lat)).toFloat(),
        )
    }
}
