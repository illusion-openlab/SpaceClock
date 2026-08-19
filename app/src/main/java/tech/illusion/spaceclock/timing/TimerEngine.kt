package tech.illusion.spaceclock.timing

enum class TimerMode { STOPWATCH, COUNTDOWN }

/**
 * 秒表 / 倒计时的纯状态机。
 *
 * nowMs 一律由调用方传入（生产环境传 SystemClock.elapsedRealtime()，单调、不受改系统时间影响）。
 * 引擎内部绝不读时钟——这是它能被单测的前提，也是"计时状态唯一真源"能搬进服务的前提。
 */
class TimerEngine {
    var mode: TimerMode = TimerMode.STOPWATCH
        private set

    private var accumulatedMs = 0L      // 已结算的时长
    private var startedAtMs: Long? = null   // 本段起点；null 表示没在跑
    private var countdownTotalMs = 0L
    private var lastLapTotalMs = 0L     // 上一次计次时的累计值，用来算分段差

    private val _laps = mutableListOf<Long>()
    val laps: List<Long> get() = _laps

    val isRunning: Boolean get() = startedAtMs != null

    fun start(nowMs: Long) {
        if (isRunning) return
        startedAtMs = nowMs
    }

    fun pause(nowMs: Long) {
        val s = startedAtMs ?: return
        accumulatedMs += nowMs - s
        startedAtMs = null
    }

    fun reset() {
        accumulatedMs = 0L
        startedAtMs = null
        lastLapTotalMs = 0L
        _laps.clear()
    }

    fun elapsedMs(nowMs: Long): Long =
        accumulatedMs + (startedAtMs?.let { nowMs - it } ?: 0L)

    /** 计次存**分段差值**（本段用了多久），不是累计值——用户要看的是每段快慢。 */
    fun lap(nowMs: Long) {
        val total = elapsedMs(nowMs)
        _laps += total - lastLapTotalMs
        lastLapTotalMs = total
    }

    /**
     * 设置倒计时时长。会**停下计时并清零**：改时长时还在跑会产生"改了一半还在走"的歧义状态，
     * 用户也无法判断剩余时间是按新时长还是旧时长算的。
     */
    fun setCountdown(totalMs: Long) {
        mode = TimerMode.COUNTDOWN
        countdownTotalMs = totalMs
        reset()
    }

    fun remainingMs(nowMs: Long): Long =
        (countdownTotalMs - elapsedMs(nowMs)).coerceAtLeast(0L)

    fun isFinished(nowMs: Long): Boolean =
        mode == TimerMode.COUNTDOWN && countdownTotalMs > 0L && elapsedMs(nowMs) >= countdownTotalMs
}
