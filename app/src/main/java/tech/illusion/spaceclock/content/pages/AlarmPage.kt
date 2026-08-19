package tech.illusion.spaceclock.content.pages

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.HorizontalDivider
import com.pico.spatial.ui.design.ListItem
import com.pico.spatial.ui.design.ListItemDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Switch
import com.pico.spatial.ui.design.Text
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.Repeat
import tech.illusion.spaceclock.theme.TABULAR_FIGURES

/**
 * 「闹钟」页：列表 + 同页内编辑子视图（不开新窗口）。
 *
 * 持久化入口不在本页——增删改开关都通过 [onAlarmsChanged] 把整份列表交回 `ClockVolume`，
 * 由它写 `ClockStore.alarms`，和 WorldPage/FocusPage 的持久层访问约定一致
 * （持久化入口集中在 ClockVolume 一处，见 ClockVolume.kt 顶部注释）。
 */
@Composable
fun AlarmPage(alarms: List<Alarm>, onAlarmsChanged: (List<Alarm>) -> Unit) {
    // 非空 = 正在编辑（新建或修改）某个闹钟；isNewAlarm 区分保存时是 append 还是替换。
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var isNewAlarm by remember { mutableStateOf(false) }

    val current = editing
    if (current != null) {
        AlarmEditor(
            alarm = current,
            isNew = isNewAlarm,
            onSave = { updated ->
                onAlarmsChanged(
                    if (isNewAlarm) alarms + updated
                    else alarms.map { if (it.id == updated.id) updated else it },
                )
                editing = null
            },
            onDelete = {
                onAlarmsChanged(alarms.filter { it.id != current.id })
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("闹钟", style = typo.titleLarge, color = scheme.labelPrimary)
        Spacer(Modifier.height(8.dp))

        if (alarms.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有闹钟 —— 点击下方“新建闹钟”",
                    style = typo.bodyMedium,
                    color = scheme.labelTertiary,
                )
            }
        } else {
            val sorted = remember(alarms) { alarms.sortedWith(compareBy({ it.hour }, { it.minute })) }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(items = sorted, key = { it.id }) { alarm ->
                        AlarmRow(
                            alarm = alarm,
                            onClick = {
                                editing = alarm
                                isNewAlarm = false
                            },
                            onToggle = { checked ->
                                onAlarmsChanged(
                                    alarms.map { if (it.id == alarm.id) it.copy(enabled = checked) else it },
                                )
                            },
                        )
                        HorizontalDivider(color = scheme.dividerLine)
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                editing = Alarm(
                    id = System.currentTimeMillis(),
                    hour = 7,
                    minute = 0,
                    repeat = Repeat.Daily,
                    enabled = true,
                )
                isNewAlarm = true
            },
            modifier = Modifier.fillMaxWidth().onGloballyPositioned { c ->
                Log.i(
                    "AlarmLayout",
                    "newAlarm w=${c.size.width} h=${c.size.height} " +
                        "x=${c.positionInRoot().x} y=${c.positionInRoot().y}",
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = scheme.fillTertiary),
        ) {
            Text("＋ 新建闹钟", color = scheme.labelPrimary)
        }
    }
}

/**
 * 小时/分钟各自一对 "－"/"＋" 按钮（和 `TimerPage.CountdownSection` 的自定义时长控件同一种写法），
 * 不用 SDK 的 `Stepper`——那个组件内部把 +/- 图标包在自己的 `decorationBox` 里，拿不到独立的
 * `onGloballyPositioned` 坐标，门禁 B 需要精确坐标去点亮"到点触发"验证（见任务报告）。
 */
@Composable
private fun NumberStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    val scheme = PicoTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Button(
            onClick = { onChange(value - 1) },
            modifier = Modifier.onGloballyPositioned { c ->
                Log.i(
                    "AlarmLayout",
                    "$label- w=${c.size.width} h=${c.size.height} " +
                        "x=${c.positionInRoot().x} y=${c.positionInRoot().y}",
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.fillTertiary,
                contentColor = scheme.labelPrimary,
            ),
        ) { Text("－") }
        Text(
            "%02d".format(value),
            style = PicoTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR_FIGURES),
            color = scheme.labelPrimary,
            modifier = Modifier.width(48.dp),
        )
        Button(
            onClick = { onChange(value + 1) },
            modifier = Modifier.onGloballyPositioned { c ->
                Log.i(
                    "AlarmLayout",
                    "$label+ w=${c.size.width} h=${c.size.height} " +
                        "x=${c.positionInRoot().x} y=${c.positionInRoot().y}",
                )
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = scheme.fillTertiary,
                contentColor = scheme.labelPrimary,
            ),
        ) { Text("＋") }
    }
}

/** 一行：时间（tnum，大号）+ 重复规则文案 + 开关。点击行本身进入编辑子视图。 */
@Composable
private fun AlarmRow(alarm: Alarm, onClick: () -> Unit, onToggle: (Boolean) -> Unit) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography

    ListItem(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = ListItemDefaults.listItemColors(backgroundColor = Color.Transparent),
        shape = RoundedCornerShape(10.dp),
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d:%02d".format(alarm.hour, alarm.minute),
                        style = typo.titleLarge.copy(fontFeatureSettings = TABULAR_FIGURES),
                        color = if (alarm.enabled) scheme.labelPrimary else scheme.labelTertiary,
                    )
                    Text(
                        repeatLabel(alarm.repeat),
                        style = typo.bodySmall,
                        color = scheme.labelSecondary,
                    )
                }
                Switch(checked = alarm.enabled, onCheckedChange = onToggle)
            }
        },
    )
}

/** 重复规则的展示文案。空自定义集合展示"从不"——和 nextTriggerAt 的"永不触发"语义对应。 */
private val WEEKDAY_SHORT = arrayOf("一", "二", "三", "四", "五", "六", "日")

internal fun repeatLabel(repeat: Repeat): String = when (repeat) {
    Repeat.Daily -> "每天"
    Repeat.Weekdays -> "工作日"
    Repeat.Weekends -> "周末"
    is Repeat.Days -> {
        if (repeat.isoDays.isEmpty()) "从不"
        else repeat.isoDays.sorted().joinToString("、") { "周${WEEKDAY_SHORT[it - 1]}" }
    }
}

/**
 * 同页内的编辑子视图：小时/分钟各一对 "－"/"＋" 按钮，重复规则四选一，自定义时额外弹出
 * 7 个星期切换按钮。`enabled` 字段不在这里改——列表行上的 `Switch` 已经能立即切换，
 * 编辑器只负责时间和重复规则。
 *
 * 小时/分钟两个 `NumberStepper` 竖排（各占一行），不是并排放进一个 `Row`——门禁 B 实测过
 * 并排会溢出玻璃面板可用宽度：两组 "－"/文字/"＋" 按钮加上中间的 ":" 合计比面板内容区
 * (~763px) 宽，`Row` 没有换行，最后一个按钮会被压成 0 宽（`AlarmLayout` 日志实测
 * `minute+ w=0`）。竖排后每行只有一组按钮（约 372px），稳稳落在可用宽度内。
 */
@Composable
private fun AlarmEditor(
    alarm: Alarm,
    isNew: Boolean,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val scheme = PicoTheme.colorScheme
    val typo = PicoTheme.typography

    var hour by remember(alarm.id) { mutableStateOf(alarm.hour) }
    var minute by remember(alarm.id) { mutableStateOf(alarm.minute) }
    var repeat by remember(alarm.id) { mutableStateOf(alarm.repeat) }
    val customDays = remember(alarm.id) {
        mutableStateOf((alarm.repeat as? Repeat.Days)?.isoDays ?: emptySet())
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(if (isNew) "新建闹钟" else "编辑闹钟", style = typo.titleLarge, color = scheme.labelPrimary)
        Spacer(Modifier.height(16.dp))

        Text("时", style = typo.bodySmall, color = scheme.labelSecondary)
        NumberStepper(
            label = "hour",
            value = hour,
            onChange = { hour = it.mod(24) },
        )
        Spacer(Modifier.height(12.dp))
        Text("分", style = typo.bodySmall, color = scheme.labelSecondary)
        NumberStepper(
            label = "minute",
            value = minute,
            onChange = { minute = it.mod(60) },
        )

        Spacer(Modifier.height(20.dp))
        Text("重复", style = typo.bodyMedium, color = scheme.labelSecondary)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RepeatChoiceButton("每天", repeat is Repeat.Daily) { repeat = Repeat.Daily }
            RepeatChoiceButton("工作日", repeat is Repeat.Weekdays) { repeat = Repeat.Weekdays }
            RepeatChoiceButton("周末", repeat is Repeat.Weekends) { repeat = Repeat.Weekends }
            RepeatChoiceButton("自定义", repeat is Repeat.Days) {
                repeat = Repeat.Days(customDays.value)
            }
        }

        if (repeat is Repeat.Days) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                WEEKDAY_SHORT.forEachIndexed { index, label ->
                    val iso = index + 1
                    val selected = iso in customDays.value
                    RepeatChoiceButton(label, selected) {
                        customDays.value = if (selected) customDays.value - iso else customDays.value + iso
                        repeat = Repeat.Days(customDays.value)
                    }
                }
            }
        }

        Box(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSave(alarm.copy(hour = hour, minute = minute, repeat = repeat)) },
                modifier = Modifier.onGloballyPositioned { c ->
                    Log.i(
                        "AlarmLayout",
                        "save w=${c.size.width} h=${c.size.height} " +
                            "x=${c.positionInRoot().x} y=${c.positionInRoot().y}",
                    )
                },
            ) { Text("保存") }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(
                    containerColor = scheme.fillTertiary,
                    contentColor = scheme.labelPrimary,
                ),
            ) { Text("取消") }
            if (!isNew) {
                Button(
                    onClick = onDelete,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error),
                ) { Text("删除") }
            }
        }
    }
}

@Composable
private fun RepeatChoiceButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = PicoTheme.colorScheme
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) scheme.interaction else scheme.fillTertiary,
            contentColor = if (selected) scheme.labelPrimaryLight else scheme.labelPrimary,
        ),
    ) { Text(label, style = PicoTheme.typography.labelMedium) }
}
