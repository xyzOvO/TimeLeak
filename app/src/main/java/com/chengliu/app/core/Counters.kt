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
 * 每 5 秒批量落盘一次，StateFlow 仍实时更新（只是一次赋值，不碰磁盘）。
 */
object Counters {
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
    @Volatile
    private var memDay: String = ""

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
        memDay = LocalDate.now().toString()
        qMem.set(a[0]); bMem.set(a[1]); rMem.set(a[2]); tMem.set(a[3])
        _today.value = qMem.get() to bMem.get()
        _bypass.value = rMem.get()
        _throttled.value = tMem.get()
        flusher.scheduleAtFixedRate({ flushLocked() }, 5, 5, TimeUnit.SECONDS)
    }

    private fun read(): LongArray {
        val p = sp ?: return longArrayOf(0, 0, 0, 0)
        if (p.getString("day", "") != LocalDate.now().toString()) return longArrayOf(0, 0, 0, 0)
        return longArrayOf(
            p.getLong("q", 0),
            p.getLong("b", 0),
            p.getLong("r", 0),
            p.getLong("t", 0),
        )
    }

    fun add(queries: Long = 0, blocked: Long = 0, bypass: Long = 0, throttled: Long = 0) {
        val day = LocalDate.now().toString()
        if (day != memDay) rollDay(day)
        if (queries != 0L) qMem.addAndGet(queries)
        if (blocked != 0L) bMem.addAndGet(blocked)
        if (bypass != 0L) rMem.addAndGet(bypass)
        if (throttled != 0L) tMem.addAndGet(throttled)
        _today.value = qMem.get() to bMem.get()
        _bypass.value = rMem.get()
        _throttled.value = tMem.get()
    }

    /** 跨天只在极偶发的边界触发，加锁安全；先把旧的一天落盘再清零内存。 */
    @Synchronized
    private fun rollDay(day: String) {
        if (day == memDay) return
        flushLocked()
        memDay = day
        qMem.set(0); bMem.set(0); rMem.set(0)
    }

    @Synchronized
    private fun flushLocked() {
        val p = sp ?: return
        val day = memDay.ifEmpty { LocalDate.now().toString() }
        p.edit().putString("day", day)
            .putLong("q", qMem.get()).putLong("b", bMem.get())
            .putLong("r", rMem.get()).putLong("t", tMem.get())
            .apply()
    }

    /** 进程退出前或切换时调用，把内存里的数字落盘，避免丢失。 */
    fun flush() = flushLocked()

    fun clear() {
        qMem.set(0); bMem.set(0); rMem.set(0); tMem.set(0)
        memDay = LocalDate.now().toString()
        _today.value = 0L to 0L
        _bypass.value = 0L
        _throttled.value = 0L
        flushLocked()
    }
}
