package tech.illusion.spaceclock.content

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.foundation.material.backgroundMaterial
import com.pico.spatial.ui.platform.Material
import kotlinx.coroutines.delay
import tech.illusion.spaceclock.content.pages.AlarmPage
import tech.illusion.spaceclock.content.pages.FocusPage
import tech.illusion.spaceclock.content.pages.HomePage
import tech.illusion.spaceclock.content.pages.TimerPage
import tech.illusion.spaceclock.content.pages.WorldPage
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 三层 C 布局：背景层是全尺寸的地球 `SpatialView`，上面叠一层 2D UI（左栏时钟 40% + 右侧
 * 玻璃面板 56%，间距 4%），底部再叠一条导航按钮。地球留给 Task 4，本任务先放空壳。
 */
@Composable
fun ClockVolume() {
    var page by remember { mutableStateOf(ClockPage.HOME) }
    // 墙上时间显示用 ZonedDateTime.now()（Global Constraints）：时区在取值源头就显式化，
    // 避免 Task 3 世界时钟 / Task 9 状态行继承隐式默认时区的写法。LeftColumn 的参数类型仍是
    // LocalTime——只换取值来源，不换接口，行为不变（两者都经系统默认时区，精确到秒一致）。
    var now by remember { mutableStateOf(ZonedDateTime.now().toLocalTime()) }

    // 每秒一跳。禁止用 withFrameNanos——它在 WindowContainer 里只触发一次。
    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now().toLocalTime()
            delay(1000L - System.currentTimeMillis() % 1000L) // 对齐到整秒，避免跳秒抖动
        }
    }

    // 门禁 B 的裁切类判定项（按钮条不被裁、左右不重叠）不靠模拟器截图——那台相机固定窄视角，
    // 一帧最多装下 2/5 个按钮，看不到全貌。改用真实测得的坐标：记录根容器与三个子层的宽度/
    // 位置，事后用数字核对右边缘是否越过容器宽度。留在代码里成本很低，Task 3/9 等后续任务
    // 改这块布局时也用得上，所以不做完就摘掉。
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { c ->
            Log.i("ClockLayout", "root w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
        }
    ) {
        EarthView(Modifier.fillMaxSize()) // Task 4 填充内容
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            LeftColumn(
                now,
                statusLineFor(page),
                Modifier.weight(0.40f).fillMaxHeight().onGloballyPositioned { c ->
                    Log.i("ClockLayout", "leftColumn w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
                },
            )
            Spacer(Modifier.weight(0.04f))
            Box(
                Modifier.weight(0.56f).fillMaxHeight()
                    .backgroundMaterial(enable = true, style = Material.Thin)
                    .clip(RoundedCornerShape(16.dp))
                    .onGloballyPositioned { c ->
                        Log.i("ClockLayout", "glassPanel w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
                    }
            ) {
                when (page) {
                    ClockPage.HOME -> HomePage()
                    ClockPage.WORLD -> WorldPage()
                    ClockPage.ALARM -> AlarmPage()
                    ClockPage.TIMER -> TimerPage()
                    ClockPage.FOCUS -> FocusPage()
                }
            }
        }
        NavBar(
            page,
            { page = it },
            Modifier.align(Alignment.BottomCenter).padding(20.dp).onGloballyPositioned { c ->
                Log.i("ClockLayout", "navBar w=${c.size.width} h=${c.size.height} x=${c.positionInRoot().x} y=${c.positionInRoot().y}")
            },
        )
    }
}

/**
 * 状态副行：设计文档 §3 的表。占位会被逐任务替换成真实数据——
 * HOME→Task 2、WORLD→Task 9、ALARM→Task 7、TIMER→Task 5、FOCUS→Task 6。
 */
private fun statusLineFor(page: ClockPage): String = when (page) {
    ClockPage.HOME -> "—"
    ClockPage.WORLD -> "—"
    ClockPage.ALARM -> "—"
    ClockPage.TIMER -> "—"
    ClockPage.FOCUS -> "—"
}
