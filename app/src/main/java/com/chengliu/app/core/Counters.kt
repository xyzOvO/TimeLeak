package com.chengliu.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 只保留三个整数：今日查询数、今日拦截数、今日接管的绕过尝试数。
 * 落盘的永远只有这三个数；域名只会在 BlockLog 的内存表里短暂停留，进程结束即消失。
 *
 * 旧实现每条 DNS 包都做一次完整的 SharedPreferences 读改写 + apply()，
 * 124 万次查询就是 124 万次磁盘事务，是耗电的重要来源。现改为内存累加 +
 * 每 5 秒批量落盘一次。
 *
 * v1.4.0 又把两处高频动作砍掉：
 * 1. 每条包不再 `LocalDate.now().toString()`（每条包一个字符串 + 一次时区换算），
 *    改成「每秒最多取一次「今天是第几天」，其余时候读缓存」；
 * 2. 状态流不再每条包都发一次。DNS 包是每秒几十上百个的量级，
 *    以前等于让界面每秒重组几十次去显示一个「今日查询数」。现在 250ms 最多发一次，
 *    窗口内的变化由 flusher 线程在窗口末尾补发一次（trailing emit），
 *    所以数字照旧不会丢，只是最多晚 250ms 才上屏。
 */
object Counters {
    /** 状态流最小发射间隔：界面上「今日查询数」晚 250ms 上屏完全看不出来。 */
    private const val EMIT_GAP_MS = 250L

    /** 时区日期最多每秒重算一次。 */
    private const val DAY_CHECK_MS = 1000L

    private val _today = MutableStateFlow(0L to 0L)
    val today: StateFlow<Pair<Long, Long>> get() = _today

    /** 发往公共 DNS 的包被隧道接管的次数（含明文 53 与加密 DNS 的 443/853）。 */
    private val _bypass = MutableStateFlow(0L)
    val bypass: StateFlow<Long> get() = _bypass

    /** 被单域名洪流熔断挡掉的查询数（FloodGuard 吸收的查询风暴）。 */
    private val _throttled = MutableStateFlow(0L)
    val throttled: StateFlow<Long> get() = _throttled

    @Volatile
    private var sp: SharedPreferences? = null

    // 内存累加器：避免每条包都写一次 SharedPreferences（I/O 放大）。
    private val qMem = AtomicLong(0)
    private val bMem = AtomicLong(0)
    private val rMem = AtomicLong(0)
    private val tMem = AtomicLong(0)

    /** 内存里这份数字属于哪一天（epochDay）。跨天时先落盘再清零。 */
    @Volatile
    private var memEpochDay: Long = 0

    /** 时区日期缓存：上次取「今天」的时间与结果。 */
    @Volatile
    private var dayCheckedAt: Long = 0
    @Volatile
    private var dayCached: Long = 0

    /** 状态流发射节流：250ms 内只发一次，尾随补发保证最终值不丢。 */
    private val emitGate = EmitThrottle(EMIT_GAP_MS)

    @Volatile
    private var pendingEmit: Boolean = false

    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        val t = Thread(r, "chengliu-counters-flush")
        t.isDaemon = true
        t
    }

    @Synchronized
    fun init(ctx: Context) {
        // 多处会调用 init（BootReceiver / Service / MainActivity / PrivacyAudit），
        // 只初始化一次，避免重复起刷盘线程、也避免把内存里已累加的数字重新读回 SP 的旧值覆盖掉。
        if (sp != null) return
        sp = ctx.applicationContext.getSharedPreferences("chengliu_counts", Context.MODE_PRIVATE)
        val a = read()
        memEpochDay = currentEpochDay()
        qMem.set(a[0]); bMem.set(a[1]); rMem.set(a[2]); tMem.set(a[3])
        _today.value = qMem.get() to bMem.get()
        _bypass.value = rMem.get()
        _throttled.value = tMem.get()
        emitGate.markEmitted(0)
        // 用 scheduleWithFixedDelay 而不是 scheduleAtFixedRate：进程从 cached 变回 uncached 时，
        // 固定速率会把错过的周期一次性补跑成百上千次，反而制造耗电尖峰。
        flusher.scheduleWithFixedDelay({ flushLocked() }, 5, 5, TimeUnit.SECONDS)
    }

    /**
     * 今天是哪个 epochDay。时区换算 + 字符串格式化本身不便宜，
     * 而 DNS 包是每秒几十上百个，所以最多每秒算一次。
     */
    private fun currentEpochDay(): Long {
        val now = System.currentTimeMillis()
        if (dayCheckedAt != 0L && now - dayCheckedAt < DAY_CHECK_MS) return dayCached
        val d = LocalDate.now().toEpochDay()
        dayCached = d
        dayCheckedAt = now
        return d
    }

    private fun read(): LongArray {
        val p = sp ?: return longArrayOf(0, 0, 0, 0)
        val today = LocalDate.ofEpochDay(currentEpochDay()).toString()
        if (p.getString("day", "") != today) return longArrayOf(0, 0, 0, 0)
        return longArrayOf(
            p.getLong("q", 0),
            p.getLong("b", 0),
            p.getLong("r", 0),
            p.getLong("t", 0),
        )
    }

    fun add(queries: Long = 0, blocked: Long = 0, bypass: Long = 0, throttled: Long = 0) {
        val day = currentEpochDay()
        if (day != memEpochDay) rollDay(day)
        if (queries != 0L) qMem.addAndGet(queries)
        if (blocked != 0L) bMem.addAndGet(blocked)
        if (bypass != 0L) rMem.addAndGet(bypass)
        if (throttled != 0L) tMem.addAndGet(throttled)
        publish()
    }

    /**
     * 把内存里的三个数发到状态流，但每 [EMIT_GAP_MS] 最多一次。
     *
     * 窗口内的后续变化只记一个「待发」标记，并让 flusher 线程在窗口结束时补发一次；
     * 补发读的是那一刻的内存值，所以中间漏掉的更新不会丢，只是合并了。
     */
    private fun publish() {
        val now = System.nanoTime() / 1_000_000
        if (emitGate.tryAcquire(now)) {
            emit()
            return
        }
        synchronized(this) {
            if (pendingEmit) return
            pendingEmit = true
        }
        flusher.schedule({ trailingEmit() }, emitGate.waitMs(now), TimeUnit.MILLISECONDS)
    }

    /** 窗口末尾的补发。到点就发，不再看节流：这一发就是最后那次「合并后的值」。 */
    private fun trailingEmit() {
        synchronized(this) { pendingEmit = false }
        emitGate.markEmitted(System.nanoTime() / 1_000_000)
        emit()
    }

    private fun emit() {
        _today.value = qMem.get() to bMem.get()
        _bypass.value = rMem.get()
        _throttled.value = tMem.get()
    }

    /** 跨天只在极偶发的边界触发，加锁安全；先把旧的一天落盘再清零内存。 */
    @Synchronized
    private fun rollDay(day: Long) {
        if (day == memEpochDay) return
        flushLocked()
        memEpochDay = day
        qMem.set(0); bMem.set(0); rMem.set(0); tMem.set(0)
        emit()
    }

    @Synchronized
    private fun flushLocked() {
        val p = sp ?: return
        val day = if (memEpochDay > 0) memEpochDay else currentEpochDay()
        p.edit().putString("day", LocalDate.ofEpochDay(day).toString())
            .putLong("q", qMem.get()).putLong("b", bMem.get())
            .putLong("r", rMem.get()).putLong("t", tMem.get())
            .apply()
    }

    /** 进程退出前或切换时调用，把内存里的数字落盘，避免丢失。 */
    fun flush() = flushLocked()

    fun clear() {
        qMem.set(0); bMem.set(0); rMem.set(0); tMem.set(0)
        memEpochDay = currentEpochDay()
        _today.value = 0L to 0L
        _bypass.value = 0L
        _throttled.value = 0L
        flushLocked()
    }
}
