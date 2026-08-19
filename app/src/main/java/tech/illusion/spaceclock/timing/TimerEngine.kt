package tech.illusion.spaceclock.timing

/**
 * 秒表 / 倒计时的纯状态机。
 *
 * nowMs 一律由调用方传入（生产环境传 SystemClock.elapsedRealtime()，单调、不受改系统时间影响）。
 * 引擎内部绝不读时钟——这是它能被单测的前提，也是"计时状态唯一真源"能搬进服务的前提。
 *
 * 秒表用途和倒计时用途各持有一个独立实例（见 `ClockVolume`/`TimerPage`），不靠一个
 * `mode` 字段在同一个实例里切换：一个实例只有一对 `accumulatedMs`/`startedAtMs`，装不下
 * "暂停的秒表 + 正在跑的倒计时"两份并存状态，且 `setCountdown` 按规格必须停表清零——
 * 共享实例会让用户设置倒计时时把正在跑/暂停的秒表悄悄清零。原先这里有一个
 * `TimerMode` 枚举 + `mode` 字段试图让单实例兼顾两种用途，在两实例架构下已经没有
 * 任何读者，予以删除；`countdownTotalMs > 0L` 本身就是"这个实例是否已配置过倒计时
 * 时长"的信号，见 [isFinished] 与调用方的 `hasDuration` 判断。
 */
class TimerEngine {
    private var accumulatedMs = 0L      // 已结算的时长
    private var startedAtMs: Long? = null   // 本段起点；null 表示没在跑

    /** 倒计时总时长；0 表示这个实例还没配置过倒计时（也是 [isFinished] 判定的一部分）。
     *  只在类内部用（remainingMs/isFinished/setCountdown），不对外暴露具体毫秒数——
     *  外部唯一需要的信号是"是否已配置过"，见 [hasDuration]。 */
    private var countdownTotalMs: Long = 0L

    /** 唯一的外部消费点是"这个实例是否已配置过倒计时时长"，不需要暴露具体毫秒数。 */
    val hasDuration: Boolean get() = countdownTotalMs > 0L

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
        countdownTotalMs = totalMs
        reset()
    }

    fun remainingMs(nowMs: Long): Long =
        (countdownTotalMs - elapsedMs(nowMs)).coerceAtLeast(0L)

    fun isFinished(nowMs: Long): Boolean =
        countdownTotalMs > 0L && elapsedMs(nowMs) >= countdownTotalMs
}
