package tech.illusion.spaceclock.store

import android.content.Context
import java.time.LocalDate
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.parseAlarms
import tech.illusion.spaceclock.alarm.serializeAlarms
import tech.illusion.spaceclock.timing.PomodoroConfig

/**
 * SharedPreferences 持久化。工作区已验证这条路径跨进程重启存活（StockQuote 的自选股）。
 *
 * 今日统计存 date + count + focusedMs 三个键，读取时对比 LocalDate.now()：
 * 跨天归零靠**读取时判定**，不靠后台定时任务——设备在午夜可能是关机的，
 * 定时任务不一定跑得到，而读取时判定永远正确。
 */
class ClockStore(context: Context) {
    private val prefs = context.getSharedPreferences("spaceclock", Context.MODE_PRIVATE)

    /** Unit Separator 分隔，不用逗号：城市名里可能带标点，逗号会切错。 */
    private val sep = ''

    /**
     * 本地城市名。决定时钟页天气卡与状态副行显示哪座城。
     *
     * 为什么需要这个字段：V1 不申请定位权限（设计文档 §1），所以"本地"只能由用户指定。
     * 默认北京；用户在世界时钟页用「设为本地」改。Task 2 曾临时用字面量 "本地" 占位，接上后要删掉。
     */
    var homeCityName: String
        get() = prefs.getString(KEY_HOME_CITY, "北京")!!
        set(value) = prefs.edit().putString(KEY_HOME_CITY, value).apply()

    var selectedCityNames: List<String>
        get() = prefs.getString(KEY_CITIES, null)
            ?.split(sep)?.filter { it.isNotBlank() }
            ?: listOf("北京", "东京", "伦敦", "纽约")     // 首次启动的默认四城
        set(value) =
            prefs.edit().putString(KEY_CITIES, value.joinToString(sep.toString())).apply()

    var pomodoroConfig: PomodoroConfig
        get() = PomodoroConfig(
            workMin = prefs.getInt(KEY_WORK, 25),
            shortMin = prefs.getInt(KEY_SHORT, 5),
            longMin = prefs.getInt(KEY_LONG, 15),
        )
        set(v) = prefs.edit()
            .putInt(KEY_WORK, v.workMin).putInt(KEY_SHORT, v.shortMin).putInt(KEY_LONG, v.longMin)
            .apply()

    /** 闹钟列表用 JSON 存：字段会随 Repeat 类型变化，分隔符拼串撑不住。 */
    var alarms: List<Alarm>
        get() = runCatching { parseAlarms(prefs.getString(KEY_ALARMS, "[]")!!) }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString(KEY_ALARMS, serializeAlarms(value)).apply()

    /** 返回 (今日完成数, 今日专注毫秒)。日期不匹配时返回零值。 */
    fun loadTodayStats(today: LocalDate): Pair<Int, Long> {
        val saved = prefs.getString(KEY_STATS_DATE, null)
        if (saved != today.toString()) return 0 to 0L
        return prefs.getInt(KEY_STATS_COUNT, 0) to prefs.getLong(KEY_STATS_MS, 0L)
    }

    fun saveTodayStats(today: LocalDate, count: Int, focusedMs: Long) {
        prefs.edit()
            .putString(KEY_STATS_DATE, today.toString())
            .putInt(KEY_STATS_COUNT, count)
            .putLong(KEY_STATS_MS, focusedMs)
            .apply()
    }

    private companion object {
        const val KEY_CITIES = "cities"
        const val KEY_HOME_CITY = "home_city"
        const val KEY_ALARMS = "alarms"
        const val KEY_WORK = "pomo_work"
        const val KEY_SHORT = "pomo_short"
        const val KEY_LONG = "pomo_long"
        const val KEY_STATS_DATE = "stats_date"
        const val KEY_STATS_COUNT = "stats_count"
        const val KEY_STATS_MS = "stats_ms"
    }
}
