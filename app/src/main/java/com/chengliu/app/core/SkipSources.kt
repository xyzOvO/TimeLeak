package com.chengliu.app.core

/**
 * 跳过广告的内置知识与默认规则源。
 *
 * 这个 file 决定了「装上就能用」：不依赖任何第三方 App 导出规则，
 * 也不要求用户自己去 GitHub 翻订阅地址。
 *
 * 参考的实现（GitHub）：
 *  - gkd-kit/gkd：按包名分组的订阅规则 + 选择器 + matchTime 时间窗。
 *  - zfdang/Android-Touch-Helper：关键词 / 控件 / 坐标三层兜底。
 *  - madeye/ad-skipper：开屏广告常在启动过渡后才渲染且不发无障碍事件，必须主动轮询。
 */
object SkipSources {

    /**
     * 默认订阅源（GKD 格式的 dist JSON5）。
     *
     * 只放确定能在国内直连的一个：npmmirror 的 GKD 官方订阅，覆盖 610 个 App。
     * raw.githubusercontent / jsDelivr 在实测中会被 TCP reset，放进来只会让
     * 用户看到「更新失败」，不如留空让人自己加。
     */
    val DEFAULT_SUBS: List<String> = listOf(
        "https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5",
    )

    /** 已知在国内直连不通的源，界面上做一次自动替换。 */
    fun isDeadSource(url: String): Boolean =
        url.contains("raw.githubusercontent.com") || url.contains("cdn.jsdelivr.net") ||
            url.contains("fastly.jsdelivr.net")

    /**
     * 广告 SDK 的「跳过按钮」控件 id 特征。
     *
     * 思路：与其为每个 App 单独写一条规则，不如直接认广告 SDK 自己的皮肤资源名。
     * 同一家 SDK 被几百个 App 接入，用的是同一批 id，而这些 id 绝不会出现在
     * 正常业务界面上 —— 命中率与安全性的性价比远高于匹配「跳过」两个字。
     *
     * 只取 id 的最后一段，转小写后做「包含」匹配。
     */
    private val SDK_ID_TOKENS: List<String> = listOf(
        // 穿山甲 / 字节：tt_splash_skip_btn、tt_splash_skip_text、tt_splash_skip_container
        "tt_splash_skip", "tt_skip_btn", "tt_skip_view",
        // 快手：ksad_skip_view、ksad_splash_skip_container
        "ksad_skip", "ksad_splash_skip",
        // 优量汇 / 腾讯广点通：gdt_skip、qqe_skip、skip_view
        "gdt_skip", "qqe_skip", "skip_container",
        // 百度：bd_splash_skip、bd_skip
        "bd_splash_skip", "bd_skip_",
        // 小米米盟 / Sigmob
        "mimo_skip", "sig_skip", "sigmob_skip",
        // 通用皮肤名（各家 SDK 常见命名）
        "splash_skip", "skip_view", "skip_btn", "skip_icon", "skip_text",
        "btn_skip", "iv_skip", "tv_skip", "ad_skip", "skip_ad",
        // 刻意不收录 jump_btn / btn_jump：那组名字在正常业务里就是「跳转」按钮，
        // 点下去会把用户带到别处，不能当广告特征用。
    )

    /**
     * 反例：这些词出现在 id 里说明是播放器的「下一集 / 片头 / 上一曲」，
     * 不是广告。宁可不点，也不能把用户正在看的视频切走。
     */
    private val SDK_ID_DENY: List<String> = listOf(
        "next", "prev", "forward", "rewind", "intro", "outro",
        "chapter", "episode", "playlist", "song", "track", "seek",
    )

    /** 该控件 id 是否属于广告 SDK 的跳过按钮。 */
    fun sdkHit(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val seg = viewId.substringAfterLast('/').lowercase()
        if (seg.length < 4 || seg.length > 64) return false
        for (d in SDK_ID_DENY) if (seg.contains(d)) return false
        for (t in SDK_ID_TOKENS) if (seg.contains(t)) return true
        return false
    }

    /**
     * 广告 SDK 的「关闭」控件 id。
     *
     * 只收带 ad / splash / SDK 前缀的命名，不收泛化的 `close_btn`、`iv_close`：
     * 后者在普通对话框里遍地都是，点掉的就是用户自己要用的功能。
     */
    private val SDK_CLOSE_TOKENS: List<String> = listOf(
        "ad_close", "close_ad", "splash_close", "splash_x", "tt_splash_close",
        "ksad_close", "gdt_close", "banner_close", "interstitial_close",
        "promotion_close", "ad_x_btn", "ad_close_btn", "close_promotion",
    )

    /** 该控件 id 是否属于广告 SDK 的关闭按钮（含 DENY 过滤）。 */
    fun sdkCloseHit(viewId: String): Boolean {
        if (viewId.isEmpty()) return false
        val seg = viewId.substringAfterLast('/').lowercase()
        if (seg.length < 4 || seg.length > 64) return false
        for (d in SDK_ID_DENY) if (seg.contains(d)) return false
        for (t in SDK_CLOSE_TOKENS) if (seg.contains(t)) return true
        return false
    }

    /**
     * 「明确的广告关闭信号」——应用内弹窗轮唯一的入场券。
     * 纯 `×` 图标不算：信息流「不感兴趣」、图片关闭、购物车移除都长这样。
     */
    private val EXPLICIT_AD_CLOSE = listOf(
        "关闭广告", "关闭此广告", "关闭该广告", "广告关闭", "跳过广告", "跳过此广告",
        "关闭推广", "关闭该推广", "推广关闭", "关闭插屏", "跳过推广",
        "close ad", "skip ad", "skip ads", "close advertisement",
    )

    fun explicitAdClose(text: String, desc: String, id: String): Boolean {
        if (sdkCloseHit(id)) return true
        for (raw in arrayOf(text, desc)) {
            val s = raw.trim().lowercase()
            if (s.isEmpty() || s.length > 12) continue
            if (EXPLICIT_AD_CLOSE.any { s.contains(it) }) return true
        }
        return false
    }

    /**
     * 四角关闭按钮：不写死「右上角」，允许不同 App 把按钮放在任意一角。
     * 但仍要求控件够小 —— 大面积的「×」多半是内容区，不是角落里的跳过按钮。
     */
    fun closeCornerHit(
        s: String,
        left: Int, top: Int, right: Int, bottom: Int,
        screenW: Int, screenH: Int,
    ): Boolean {
        val t = s.trim()
        if (t != "×" && t != "✕" && t != "x" && t != "X" && t != "✖" && t != "关闭") return false
        if (screenW <= 0 || screenH <= 0) return false
        if (right - left > screenW * 0.16f || bottom - top > screenH * 0.10f) return false
        val nearLeft = right < screenW * 0.30f
        val nearRight = left > screenW * 0.70f
        val nearTop = top < screenH * 0.30f
        val nearBottom = bottom > screenH * 0.78f
        return (nearLeft || nearRight) && (nearTop || nearBottom)
    }

    /**
     * 纯图标关闭按钮（"×" / "✕" / "X"）。
     * 只在开屏时间窗内、且位于屏幕右上角时才算数 —— 中间一个大叉多半是
     * 弹窗的「关闭内容」，不是广告的跳过。
     */
    fun cornerCloseHit(text: String, right: Int, top: Int, screenW: Int, screenH: Int): Boolean {
        val s = text.trim()
        if (s != "×" && s != "✕" && s != "x" && s != "X" && s != "✖") return false
        if (screenW <= 0 || screenH <= 0) return false
        return right > screenW * 0.6f && top < screenH * 0.35f
    }
}
