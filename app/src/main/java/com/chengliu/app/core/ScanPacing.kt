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
 * - 连续几轮什么都没点到 → 合并窗口 120 → 240 → 360 → 480ms 逐级退；
 * - 命中、换窗口、用户自己动手点/滑 → 立刻回 120ms；
 * - 开屏窗也退，但封在 splashMaxMs（默认 240ms）：开屏要速度，可也不能拿
 *   「每秒扫八次、连扫十几秒」去换那几十毫秒。真机实测（某应用商店的信息流
 *   视频页）开屏窗里 12 秒扫了近百次，其中一次候选都没有。
 * - 判据必须是「有没有点到」，不能是「有没有候选」：开屏广告页几乎每一轮都能
 *   数出几个窄小候选（倒计时、跳过、关闭），拿候选数当信号等于永远不复位，
 *   退避在真正需要它的页面上从不生效 —— 这是 v1.4.0 真机实测出来的。
 *
 * 纯逻辑、不碰 Android，JVM 单测直接验（见 ScanPacingTest）。
 */
class ScanPacer(
    private val baseMs: Long = 120L,
    private val stepMs: Long = 120L,
    private val maxMs: Long = 480L,
    /** 开屏窗内允许的最大合并窗口：比普通页面小，比「不合并」慢得有限。 */
    private val splashMaxMs: Long = 240L,
) {
    private var idle = 0

    /** 当前该等多久再扫。 */
    val mergeDelayMs: Long get() = (baseMs + stepMs * idle).coerceAtMost(maxMs)

    /** 连续空转轮数，观测用。 */
    val idleStreak: Int get() = idle

    /**
     * 一轮扫描结束，按结果调整退避等级并返回下一次的合并窗口。
     * @param hit 这一轮是否真的点下去了（点了 / 按了返回）
     * @param splash 这一轮是否处在开屏窗：照样退避，但合并窗口封在 splashMaxMs
     */
    fun onScan(hit: Boolean, splash: Boolean): Long {
        idle = if (hit) 0 else (idle + 1).coerceAtMost(MAX_STEPS)
        val delay = mergeDelayMs
        return if (splash) delay.coerceAtMost(splashMaxMs) else delay
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
