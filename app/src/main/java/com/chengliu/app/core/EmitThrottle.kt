package com.chengliu.app.core

/**
 * 「最多每 gap 毫秒发一次」的纯逻辑闸门。
 *
 * 抽出来是因为它要能被单测按假时钟跑：DNS 计数每秒几十上百次更新，
 * 真正上屏的只需要 4 次/秒 —— 但哪些更新被合并、合并后最终值对不对，
 * 这些判断不该靠「在真机上看着还行」来验证。
 *
 * 用法：
 * ```
 * if (gate.tryAcquire(now)) emit()                     // 窗口已过，直接发
 * else schedule({ gate.markEmitted(now2); emit() }, gate.waitMs(now))  // 窗口末尾补发
 * ```
 */
class EmitThrottle(private val gapMs: Long = 250L) {

    private var lastAt: Long = Long.MIN_VALUE / 4

    /** 现在能否发射？能则占位并返回 true。 */
    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - lastAt >= gapMs) {
            lastAt = nowMs
            return true
        }
        return false
    }

    /** 还要等多少毫秒才到下一个窗口（至少 1ms，避免排一个 0 延迟的空转任务）。 */
    @Synchronized
    fun waitMs(nowMs: Long): Long {
        val left = lastAt + gapMs - nowMs
        return if (left < 1L) 1L else left
    }

    /** 尾随补发真的发了之后登记时间，让下一个窗口从这一刻重新算。 */
    @Synchronized
    fun markEmitted(nowMs: Long) {
        lastAt = nowMs
    }
}
