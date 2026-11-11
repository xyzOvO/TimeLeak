package com.chengliu.app.core

import kotlin.math.abs

/**
 * 点击去重时判断「这一轮看到的是不是上一次点过的同一个控件」。
 *
 * 抽成零 Android 依赖的纯函数，是为了能在 JVM 单测里锁住边界：这段逻辑原先
 * 埋在 AccessibilityService 内部，改回精确坐标也不会被任何测试发现。
 *
 * 倒计时按钮从「跳过 10s」变「跳过 9s」时会左右挪几像素；点击前的
 * verifyBeforeClick 允许 [DRIFT_PX] 漂移，判重必须用同一容差，否则
 * 「点完没跳走再试一次」会被当成另一个控件而拒绝重试。
 */
object ClickTarget {
    const val DRIFT_PX = 16

    fun same(
        base: String,
        left: Int,
        top: Int,
        lastBase: String,
        lastLeft: Int,
        lastTop: Int,
    ): Boolean = base == lastBase &&
        abs(left - lastLeft) <= DRIFT_PX &&
        abs(top - lastTop) <= DRIFT_PX
}
