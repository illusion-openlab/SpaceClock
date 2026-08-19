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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * 三层 C 布局：背景层是全尺寸的地球 `SpatialView`，上面叠一层 2D UI（左栏时钟 40% + 右侧
 * 玻璃面板 56%，间距 4%），底部再叠一条导航按钮。地球留给 Task 4，本任务先放空壳。
 */
@Composable
fun ClockVolume() {
    var page by remember { mutableStateOf(ClockPage.HOME) }
    var now by remember { mutableStateOf(LocalTime.now()) }

    // 每秒一跳。禁止用 withFrameNanos——它在 WindowContainer 里只触发一次。
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(1000L - System.currentTimeMillis() % 1000L) // 对齐到整秒，避免跳秒抖动
        }
    }

    Box(Modifier.fillMaxSize()) {
        EarthView(Modifier.fillMaxSize()) // Task 4 填充内容
        Row(Modifier.fillMaxSize().padding(20.dp)) {
            LeftColumn(now, statusLineFor(page), Modifier.weight(0.40f).fillMaxHeight())
            Spacer(Modifier.weight(0.04f))
            Box(
                Modifier.weight(0.56f).fillMaxHeight()
                    .backgroundMaterial(enable = true, style = Material.Thin)
                    .clip(RoundedCornerShape(16.dp))
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
        NavBar(page, { page = it }, Modifier.align(Alignment.BottomCenter).padding(20.dp))
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
