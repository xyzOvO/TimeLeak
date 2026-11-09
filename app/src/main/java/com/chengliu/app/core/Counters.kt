package com.chengliu.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * 只保留三个整数：今日查询数、今日拦截数、今日接管的绕过尝试数。
 * 落盘的永远只有这三个数；域名只会在 BlockLog 的内存表里短暂停留，进程结束即消失。
 */
object Counters {
    private val _today = MutableStateFlow(0L to 0L)
    val today: StateFlow<Pair<Long, Long>> get() = _today

    /** 发往公共 DNS 的包被隧道接管的次数（含明文 53 与加密 DNS 的 443/853）。 */
    private val _bypass = MutableStateFlow(0L)
    val bypass: StateFlow<Long> get() = _bypass

    @Volatile
    private var sp: SharedPreferences? = null

    fun init(ctx: Context) {
        sp = ctx.applicationContext.getSharedPreferences("chengliu_counts", Context.MODE_PRIVATE)
        val (q, b, r) = read()
        _today.value = q to b
        _bypass.value = r
    }

    private fun read(): Triple<Long, Long, Long> {
        val p = sp ?: return Triple(0L, 0L, 0L)
        if (p.getString("day", "") != LocalDate.now().toString()) return Triple(0L, 0L, 0L)
        return Triple(p.getLong("q", 0), p.getLong("b", 0), p.getLong("r", 0))
    }

    fun add(queries: Long = 0, blocked: Long = 0, bypass: Long = 0) {
        val p = sp ?: return
        synchronized(this) {
            val day = LocalDate.now().toString()
            val rolled = p.getString("day", "") != day
            val q = (if (rolled) 0L else p.getLong("q", 0)) + queries
            val b = (if (rolled) 0L else p.getLong("b", 0)) + blocked
            val r = (if (rolled) 0L else p.getLong("r", 0)) + bypass
            p.edit().putString("day", day).putLong("q", q).putLong("b", b).putLong("r", r).apply()
            _today.value = q to b
            _bypass.value = r
        }
    }

    fun clear() {
        val p = sp ?: return
        synchronized(this) {
            p.edit()
                .putString("day", LocalDate.now().toString())
                .putLong("q", 0).putLong("b", 0).putLong("r", 0)
                .apply()
            _today.value = 0L to 0L
            _bypass.value = 0L
        }
    }
}
