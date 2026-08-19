package tech.illusion.spaceclock.theme

import androidx.compose.ui.graphics.Color
import com.pico.spatial.ui.design.ColorScheme
import com.pico.spatial.ui.design.defaultColorScheme

/**
 * 深空蓝固定配色，来自设计文档 §5.4.1。
 *
 * 不用 PicoTheme 的系统自适应默认方案：那套在真机上跑出来是"没有主题色的通用浅色 UI"，
 * 工作区已复现三次（SpaceLianliankan / Overwinter / SpaceWhiteboard）。
 */
val SpaceClockColorScheme: ColorScheme = defaultColorScheme(
    fillPrimary = Color(0xFF5AAAF0),
    fillSecondary = Color(0xFF080E17),
    fillTertiary = Color(0xFF0E1626),
    fillLight = Color(0x175A96DC),
    labelPrimary = Color(0xFFE8F2FF),
    labelPrimaryLight = Color(0xFF080E17),
    labelSecondary = Color(0xFF8BA8C9),
    labelTertiary = Color(0x998BA8C9),
    labelQuaternary = Color(0x4D8BA8C9),
    lightenHover = Color(0x1478BEFF),
    lightenPressed = Color(0x2478BEFF),
    error = Color(0xFFE5484D),
    alert = Color(0xFFE9A23B),
    passable = Color(0xFF3DBE8B),
    interaction = Color(0xFF5AAAF0),
    dividerLine = Color(0x3378AAE6),
)

/**
 * 等宽数字。SpatialUI 没有 tabular-figures 参数，唯一写法是塞进 TextStyle.fontFeatureSettings。
 * 用常量而不是到处写字面量：标签拼错不报错，只会静默失效。
 * **仍未验证**：PICO 系统字体是否真带 tnum GSUB 表——单张截图看不出来，要看跨位数时宽度是否稳定。
 */
const val TABULAR_FIGURES = "tnum"

/**
 * 城市标记点颜色，刻意不用 fillPrimary。
 * 设计文档「配色选择」变更记录：深空蓝强调色和地球贴图同色系，标记点用它会在蓝色球面上认不出来。
 * 这是 ECS 材质用的原始 ARGB——UnlitMaterial 跑在 Compose 之外，读不到 CompositionLocal。
 */
const val PIN_COLOR_ARGB: Int = 0xFFFFE9B8.toInt()
