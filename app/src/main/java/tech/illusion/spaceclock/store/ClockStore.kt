package tech.illusion.spaceclock.store

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import tech.illusion.spaceclock.alarm.Alarm
import tech.illusion.spaceclock.alarm.parseAlarms
import tech.illusion.spaceclock.alarm.serializeAlarms
import tech.illusion.spaceclock.time.CURRENT_LOCATION_CITY_NAME
import tech.illusion.spaceclock.time.City
import tech.illusion.spaceclock.timing.PomodoroConfig

/**
 * 暂停中的秒表快照（用户裁决②）。[laps] 存的是分段差，与 `TimerEngine.laps` 同一口径。
 */
data class StopwatchSnapshot(val elapsedMs: Long, val laps: List<Long>)

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
     * 默认值是 [CURRENT_LOCATION_CITY_NAME]（"当前位置"，2026-08-21 设计文档）——这只在
     * `SharedPreferences` 的 `KEY_HOME_CITY` 键从未被写过时生效。写入只发生在用户点「设为本地」
     * 的那一刻：一旦写过（哪怕手动选的还是"当前位置"），这次选择永久优先于默认值，不会被
     * 后续的自动定位悄悄覆盖。V1 阶段这里默认过北京、不申请定位权限；现在默认值换成当前位置，
     * 但依然不申请 Android 定位权限——走的是已有的 IP 地理定位（`LocationRepository`）。
     */
    var homeCityName: String
        get() = prefs.getString(KEY_HOME_CITY, CURRENT_LOCATION_CITY_NAME)!!
        set(value) = prefs.edit().putString(KEY_HOME_CITY, value).apply()

    var selectedCityNames: List<String>
        get() = prefs.getString(KEY_CITIES, null)
            ?.split(sep)?.filter { it.isNotBlank() }
            ?: listOf("北京", "东京", "伦敦", "纽约")     // 首次启动的默认四城
        set(value) =
            prefs.edit().putString(KEY_CITIES, value.joinToString(sep.toString())).apply()

    /**
     * IP 定位合成的"当前位置"城市；null 表示从未成功定位过。跟 [selectedCityNames]/[homeCityName]
     * 一样是纯值持久化，不是"是否已经问过定位权限"那类一次性标记——这里存的是最近一次成功定位的结果，
     * 每次"使用当前位置"/"重新定位"成功都会覆盖它。
     */
    var currentLocationCity: City?
        get() = parseCurrentLocationCity(prefs.getString(KEY_CURRENT_LOCATION_CITY, null))
        set(value) = prefs.edit()
            .putString(KEY_CURRENT_LOCATION_CITY, serializeCurrentLocationCity(value))
            .apply()

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

    /**
     * 是否已经问过 `POST_NOTIFICATIONS`。**跨进程持久**（用户裁决①：只问一次）——
     * 用内存标记不行：`LaunchedEffect(Unit)` 在同一进程里重挂载一次就会再问一次，
     * 而重开窗口本身就会重挂载（Task 8 的 `task8-logcat.txt` 里同一个 pid 出现两条请求日志）。
     */
    var notificationPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_ASKED, value).apply()

    /**
     * 未确认的倒计时「时间到」。到点时置位、用户点「知道了」（或重设时长）时清除。
     *
     * 为什么要落盘：服务在全空闲时会 `stopSelf()`，窗口也已经关了，那条只活在内存里的
     * 「时间到」会随进程一起消失——用户设了 5 分钟倒计时、关掉窗口、回来发现它凭空没了。
     * 落盘后重开时补弹（**不重发通知**，通知是到点那一刻的事）。
     */
    var countdownAlertPending: Boolean
        get() = prefs.getBoolean(KEY_COUNTDOWN_ALERT, false)
        set(value) = prefs.edit().putBoolean(KEY_COUNTDOWN_ALERT, value).apply()

    /**
     * 未确认的到点闹钟 id 队列，顺序即卡片展示顺序。和上面的倒计时同一个道理：
     * 窗口关着的时候闹钟到点，那条提醒不能只活在服务内存里。
     */
    var pendingAlarmAlertIds: List<Long>
        get() = prefs.getString(KEY_PENDING_ALARMS, null)
            ?.split(sep)?.mapNotNull { it.toLongOrNull() }
            ?: emptyList()
        set(value) =
            prefs.edit().putString(KEY_PENDING_ALARMS, value.joinToString(sep.toString())).apply()

    /**
     * 暂停中的秒表快照；null 表示没有可恢复的秒表（从未跑过、已复位、或正在跑）。
     *
     * "正在跑"不落盘：跑着的秒表由前台服务保证存活，而它的快照每 tick 都在变，落盘就变成
     * 每 200ms 一次写。进程被杀时正在跑的秒表丢失，仍是已知取舍（不在本轮裁决范围内）。
     */
    var stopwatchSnapshot: StopwatchSnapshot?
        get() {
            if (!prefs.contains(KEY_SW_ELAPSED)) return null
            val laps = prefs.getString(KEY_SW_LAPS, null)
                ?.split(sep)?.mapNotNull { it.toLongOrNull() } ?: emptyList()
            return StopwatchSnapshot(prefs.getLong(KEY_SW_ELAPSED, 0L), laps)
        }
        set(value) {
            val e = prefs.edit()
            if (value == null) e.remove(KEY_SW_ELAPSED).remove(KEY_SW_LAPS)
            else e.putLong(KEY_SW_ELAPSED, value.elapsedMs)
                .putString(KEY_SW_LAPS, value.laps.joinToString(sep.toString()))
            e.apply()
        }

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
        const val KEY_NOTIF_ASKED = "notif_asked"
        const val KEY_COUNTDOWN_ALERT = "countdown_alert_pending"
        const val KEY_PENDING_ALARMS = "pending_alarm_alerts"
        const val KEY_SW_ELAPSED = "sw_elapsed"
        const val KEY_SW_LAPS = "sw_laps"
        const val KEY_CURRENT_LOCATION_CITY = "current_location_city"
    }
}

/** 跟 [ClockStore] 实例里的 `sep`（`''`）同一个字符，只是这里要保持纯函数、不碰 Context。 */
private const val CURRENT_LOCATION_FIELD_SEP = ''

/** [City] -> 持久化字符串；`null` 输入原样返回 `null`（表示"清空/从未定位过"）。 */
internal fun serializeCurrentLocationCity(city: City?): String? {
    if (city == null) return null
    return listOf(city.name, city.zoneId, city.lat, city.lon, city.displayName ?: "")
        .joinToString(CURRENT_LOCATION_FIELD_SEP.toString())
}

/** 持久化字符串 -> [City]；字段数不对、数值字段解析失败都返回 `null`，不抛异常。 */
internal fun parseCurrentLocationCity(raw: String?): City? {
    if (raw == null) return null
    val parts = raw.split(CURRENT_LOCATION_FIELD_SEP.toString())
    if (parts.size != 5) return null
    return runCatching {
        val zoneId = parts[1]
        require(zoneId in ZoneId.getAvailableZoneIds())
        City(
            name = parts[0],
            zoneId = zoneId,
            lat = parts[2].toFloat(),
            lon = parts[3].toFloat(),
            displayName = parts[4].ifEmpty { null },
        )
    }.getOrNull()
}
