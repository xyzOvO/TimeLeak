package com.chengliu.app.core

/**
 * 候选排序：一屏同时出现多个「跳过」时的取舍规则。
 *
 * 起因：开屏广告会有真假两个跳过按钮（真的在 App 自己的底栏，
 * 假的是广告 SDK 画的诱饵）。按遍历顺序取第一个等于抽签，
 * 这里按「哪几个特征更像真按钮」排序，纯逻辑可单测。
 *
 * 顺序：自身可点 > 面积小 > 离屏幕角近。同级保持原遍历顺序（稳定排序）。
 */
object HitRanker {

    /** 排序用的轻量键：只读扫描阶段已有的纯数据，不额外跨进程。 */
    data class RankKey(
        val clickable: Boolean,
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val screenW: Int,
        val screenH: Int,
    ) {
        val area: Long get() =
            if (width <= 0 || height <= 0) Long.MAX_VALUE else width.toLong() * height.toLong()

        /** 控件中心到最近屏幕角的距离，越小越像「角上的小胶囊」。 */
        val cornerDistance: Long get() {
            val cx = (left + right) / 2
            val cy = (top + bottom) / 2
            val dx = minOf(kotlin.math.abs(cx), kotlin.math.abs(cx - screenW))
            val dy = minOf(kotlin.math.abs(cy), kotlin.math.abs(cy - screenH))
            return dx.toLong() * dx.toLong() + dy.toLong() * dy.toLong()
        }
    }

    fun <T> order(items: List<T>, key: (T) -> RankKey): List<T> {
        if (items.size < 2) return items
        return items.sortedWith(
            Comparator { a, b ->
                val ka = key(a)
                val kb = key(b)
                if (ka.clickable != kb.clickable) {
                    return@Comparator if (ka.clickable) -1 else 1
                }
                val byArea = ka.area.compareTo(kb.area)
                if (byArea != 0) return@Comparator byArea
                ka.cornerDistance.compareTo(kb.cornerDistance)
            },
        )
    }
}
