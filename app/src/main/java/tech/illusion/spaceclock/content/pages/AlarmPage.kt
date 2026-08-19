package tech.illusion.spaceclock.content.pages

import androidx.compose.runtime.Composable
import com.pico.spatial.ui.design.Text

/**
 * 占位页面，本任务只放一行文字。Task 后续会把签名换成真实数据参数——
 * 见 ClockVolume.kt 顶部注释里各页面对应的任务号。
 */
@Composable
fun AlarmPage() {
    Text("闹钟")
}
