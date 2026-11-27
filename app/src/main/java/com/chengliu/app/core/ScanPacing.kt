package com.chengliu.app.core

/**
 * 空转退避：连续扫了几轮什么都没发现，就把「合并窗口」慢慢拉长。
 *
 * 为什么需要它 —— 应用里的「内容变化」事件是刷屏级的：列表滚动、倒计时文本、
 * 播放器字幕、骨架屏动画，每秒能发几十条。每一条都会排队一次全树扫描，
 * 而绝大多数扫描的结局都是 EMPTY / NO_CANDIDATE。120ms 的合并窗口意味着
 * 最坏每秒 8 次全树遍历，每次都要跨进程读一遍节点属性 —— 电和帧率都花在
 * 「确认这里没有广告」上。
 *
 * 策略：
 * - 一轮扫下来一个候选都没有 → 合并窗口 120 → 240 → 360 → 480ms 逐级退；
 * - 扫到候选（画面确实在变）、命中、换窗口、或者用户自己动手点/滑 → 立刻回 120ms；
 * - 开屏窗（进页面前几秒）不走这条路，调用方直接给 0ms 立即扫，不拿广告速度换省电。
 *
 * 纯逻辑、不碰 Android，JVM 单测直接验（见 ScanPacingTest）。
 */
class ScanPacer(
    private val baseMs: Long = 120L,
    private val stepMs: Long = 120L,
    private val maxMs: Long = 480L,
) {
    private var idle = 0

    /** 当前该等多久再扫。 */
    val mergeDelayMs: Long get() = (baseMs + stepMs * idle).coerceAtMost(maxMs)

    /** 连续空转轮数，观测用。 */
    val idleStreak: Int get() = idle

    /**
     * 一轮扫描结束，按结果调整退避等级并返回下一次的合并窗口。
     * @param candidates 这一轮里的候选数（0 表示树里根本没有可点的东西）
     */
    fun onScan(candidates: Int): Long {
        idle = if (candidates > 0) 0 else (idle + 1).coerceAtMost(MAX_STEPS)
        return mergeDelayMs
    }

    /** 换窗口、用户操作、命中之后调用：立刻恢复灵敏。 */
    fun reset() {
        idle = 0
    }

    private companion object {
        /** 退避档位上限，避免整数一直涨（实际被 maxMs 截断，这里只是兜底）。 */
        const val MAX_STEPS = 16
    }
}
