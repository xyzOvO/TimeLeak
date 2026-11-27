package com.chengliu.app.core

/**
 * 几何猜测用到的两个纯逻辑判据。
 *
 * 放在这里而不是写在服务里，是为了能在 JVM 单测里验证 —— 无障碍节点搬不进测试，
 * 但「该不该猜」这个决定本身是纯函数，值得有测试守着。
 */
object AdHints {
    /** 能指向「这是一屏广告」的词：合规标（广告/推广/赞助）与热区话术（点击跳转/落地页/第三方应用）。 */
    val WORDS = listOf("广告", "推广", "赞助", "点击跳转", "落地页", "第三方应用")

    /**
     * 热区话术：广告可点区域上的引导语。
     *
     * 深挖一轮读到的文案只认这一档 —— 信息流里带个「广告」标签太常见，
     * 用它当「整屏广告」的证据会把普通页面也判成广告页。
     */
    val HOTSPOT_WORDS = listOf("点击跳转", "落地页", "第三方应用", "三方app", "点击查看")

    /** 文案里有没有广告痕迹（大小写不敏感，空文案不算）。 */
    fun looksLikeAd(text: String, desc: String): Boolean {
        val s = (text + " " + desc).lowercase()
        if (s.isBlank()) return false
        return WORDS.any { s.contains(it) }
    }

    /** 文案里有没有「整屏广告热区」的话术（比 [looksLikeAd] 更窄，专给深挖用）。 */
    fun looksLikeHotspot(text: String, desc: String): Boolean {
        val s = (text + " " + desc).lowercase()
        if (s.isBlank()) return false
        return HOTSPOT_WORDS.any { s.contains(it) }
    }
}

object GeoGuess {
    /**
     * 该不该猜一次。
     *
     * [adHint] 是「这屏确实像广告」的证据：常规遍历看到的文案，或深挖一轮后看到的深层文案。
     * 没有证据就不猜 —— 宁可漏一次，也不要在普通界面上把角落图标当成跳过按钮。
     * [clickedAnything] 为真表示这一轮已经有别的轮次点到了，不需要再猜。
     */
    fun shouldGuess(
        geoTap: Boolean,
        geoAttempted: Boolean,
        clickedAnything: Boolean,
        geoCount: Int,
        adHint: Boolean,
    ): Boolean {
        if (!geoTap || geoAttempted) return false
        if (clickedAnything) return false
        if (geoCount <= 0) return false
        return adHint
    }

    /**
     * 要不要为「有没有广告痕迹」补一次深挖。
     *
     * 有的界面文案躺在 WebView 虚拟节点的 19~20 层，常规遍历到上限就停了，
     * 于是永远看不到证据。只在真有几何候选、还没猜过、并且常规遍历没找到证据时才多花这一趟。
     */
    fun needsDeepHint(
        geoTap: Boolean,
        geoAttempted: Boolean,
        geoCount: Int,
        adHint: Boolean,
    ): Boolean {
        if (!geoTap || geoAttempted) return false
        if (geoCount <= 0) return false
        return !adHint
    }
}
