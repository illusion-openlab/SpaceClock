package tech.illusion.spaceclock.timing

import java.time.LocalDate

enum class Phase { WORK, SHORT_BREAK, LONG_BREAK }

data class PomodoroConfig(
    val workMin: Int = 25,
    val shortMin: Int = 5,
    val longMin: Int = 15,
    val roundsPerLong: Int = 4,
)

/**
 * 番茄钟阶段循环。和 TimerEngine 一样，nowMs 全部由调用方传入，内部不读时钟。
 *
 * 阶段跃迁只在 advanceTo / skip 里发生。为什么不在 remainingMs 里顺手结算：
 * remainingMs 是 UI 每帧都会调的**查询**，在查询里改状态会让"读一下"产生副作用，
 * 阶段计数就会随 UI 刷新率变化。
 */
class PomodoroEngine(config: PomodoroConfig = PomodoroConfig()) {

    /** 只能通过 [applyConfig] 改：运行中换时长会让剩余时间凭空跳变，写权限必须带那道闸。 */
    var config: PomodoroConfig = config
        private set

    var phase: Phase = Phase.WORK
        private set
    var round: Int = 1
        private set
    var completedToday: Int = 0
        private set
    var focusedTodayMs: Long = 0L
        private set

    private var accumulatedMs = 0L
    private var startedAtMs: Long? = null
    private var statsDate: LocalDate? = null

    val isRunning: Boolean get() = startedAtMs != null

    private fun phaseLenMs(): Long = when (phase) {
        Phase.WORK -> config.workMin * 60_000L
        Phase.SHORT_BREAK -> config.shortMin * 60_000L
        Phase.LONG_BREAK -> config.longMin * 60_000L
    }

    /**
     * 当前阶段的总时长。进度环的分母；`FocusPage` 原先自己复制了一份同样的换算公式，
     * 两份公式会各自漂移，改由引擎唯一提供。
     */
    val phaseTotalMs: Long get() = phaseLenMs()

    private fun elapsedMs(nowMs: Long) = accumulatedMs + (startedAtMs?.let { nowMs - it } ?: 0L)

    /**
     * 改时长配置。**运行中一律拒绝**并返回 false：换时长会让当前阶段的剩余时间凭空跳变，
     * 用户无从判断剩余时间是按新时长还是旧时长算的（和 [TimerEngine.setCountdown] 停表清零
     * 同一类问题）。以前这道闸只有 UI 的 `enabled = !running`，前台服务是第二个调用方，
     * 靠某个界面的可用态挡不住——纵深防御放在引擎里。
     */
    fun applyConfig(next: PomodoroConfig): Boolean {
        if (isRunning) return false
        config = next
        return true
    }

    fun start(nowMs: Long) { if (!isRunning) startedAtMs = nowMs }

    fun pause(nowMs: Long) {
        val s = startedAtMs ?: return
        accumulatedMs += nowMs - s
        startedAtMs = null
    }

    fun remainingMs(nowMs: Long): Long = (phaseLenMs() - elapsedMs(nowMs)).coerceAtLeast(0L)

    /** 由服务 tick 调用。用 while 而不是 if：长时间没 tick（设备睡眠）时要能一次补跨多个阶段。 */
    fun advanceTo(nowMs: Long) {
        while (elapsedMs(nowMs) >= phaseLenMs()) {
            val consumed = phaseLenMs()
            if (phase == Phase.WORK) {
                completedToday++
                focusedTodayMs += consumed      // 只有工作阶段计入专注时长，休息不算
            }
            // 把本阶段消耗掉的时间从累计里扣除，余下的滚进下一阶段——不要清零，
            // 否则跨阶段的那一点余量会被吞掉，长跑下来会累积可观的漂移。
            accumulatedMs = elapsedMs(nowMs) - consumed
            startedAtMs = if (isRunning) nowMs else null
            nextPhase()
        }
    }

    /** 跳过：切阶段但**不计完成数、不计专注时长**——没做完就是没做完。 */
    fun skip(nowMs: Long) {
        accumulatedMs = 0L
        startedAtMs = if (isRunning) nowMs else null
        nextPhase()
    }

    private fun nextPhase() {
        phase = when (phase) {
            Phase.WORK ->
                if (round % config.roundsPerLong == 0) Phase.LONG_BREAK else Phase.SHORT_BREAK
            Phase.SHORT_BREAK, Phase.LONG_BREAK -> { round++; Phase.WORK }
        }
    }

    /** 跨天归零。由调用方在读取统计前调用，不靠后台定时任务。 */
    fun rollOverIfNewDay(today: LocalDate) {
        if (statsDate != today) {
            statsDate = today
            completedToday = 0
            focusedTodayMs = 0L
        }
    }

    /** 从持久层回填今日统计。必须在 rollOverIfNewDay 之后调用，否则会被它清零。 */
    fun seedStats(date: LocalDate, count: Int, focusedMs: Long) {
        statsDate = date
        completedToday = count
        focusedTodayMs = focusedMs
    }
}
