package com.chengliu.app.core

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 单域名 DNS 洪流防护（熔断）。
 *
 * 背景：拦截域名回 NXDOMAIN 后，部分 SDK（友盟统计等）会把它当成「网络断了」死命重试，
 * 形成每秒数百次的查询风暴。这些查询全部涌进本地 VPN 的上游解析线程池，会把线程和 CPU 打爆
 * （实测友盟 430/s 把本进程压到 98%）。只把友盟加进放行名单是「头痛医头」，
 * 这里做的是通用防护：任意单域名查询速率超过阈值，就暂停转发上游、改回 SERVFAIL，
 * 让客户端立刻失败退避，上游线程池与 CPU 不再被单个域名拖垮，其它正常域名完全不受影响。
 *
 * 纯内存、每域名一把小锁，不落盘、不影响拦截/放行判定。
 */
object FloodGuard {
    private const val TAG = "FloodGuard"
    private const val WINDOW_MS = 1000L
    private const val LIMIT = 80          // 1 秒内同一域名超过 80 次查询即视为风暴
    private const val COOLDOWN_MS = 3000L // 触发后 3 秒内对该域名一律 SERVFAIL
    private const val MAX_ENTRIES = 4096
    private const val STALE_MS = 30_000L

    private class Entry(
        @Volatile var windowStart: Long,
        @Volatile var count: Int,
        @Volatile var throttledUntil: Long,
    )

    private val map = ConcurrentHashMap<String, Entry>()
    private val tMem = AtomicLong(0)

    /** 累计被熔断的查询数，供运行概览展示。 */
    val throttledTotal: Long get() = tMem.get()

    /**
     * 是否放行这一次对该域名的转发。返回 false 表示已触发熔断，调用方应直接回 SERVFAIL，
     * 不要再去上游解析（那是耗资源的动作）。
     */
    fun admit(name: String): Boolean {
        val now = System.currentTimeMillis()
        val e = map[name]
        if (e == null) {
            if (map.size > MAX_ENTRIES) sweep(now)
            map[name] = Entry(now, 1, 0L)
            return true
        }
        synchronized(e) {
            if (e.throttledUntil > now) return false
            if (now - e.windowStart >= WINDOW_MS) {
                e.windowStart = now
                e.count = 0
            }
            e.count++
            if (e.count > LIMIT) {
                e.throttledUntil = now + COOLDOWN_MS
                tMem.incrementAndGet()
                Log.w(TAG, "域名 $name 触发熔断，${COOLDOWN_MS / 1000}s 内改回 SERVFAIL")
                return false
            }
            return true
        }
    }

    /** 清理 30 秒没动静的域名，避免 map 无限增长。 */
    private fun sweep(now: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val (_, v) = it.next()
            if (now - v.windowStart > STALE_MS) it.remove()
        }
    }

    fun reset() {
        map.clear()
        tMem.set(0)
    }
}
