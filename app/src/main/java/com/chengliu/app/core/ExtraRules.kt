package com.chengliu.app.core

/**
 * 跳过广告板块里的三个「附加动作」判定，全是纯数据逻辑，不依赖 Android。
 *
 * 它们点的不是广告，而是用户明确要求代点的非广告界面，所以默认大多关闭，
 * 且**不进** `classify()` / `DecisionCore` 的广告判定链（那条链会把「我知道了」
 * 「查看原图」一律判成 UNKNOWN 硬否决）。执行时仍复用 `verifyBeforeClick()`
 * 二次验证、`mark()` 冷却 / 计数 / 去重，不新增裸点击路径。
 *
 * 三条闸门的共同约束：唯一候选（同屏出现两个就整个放弃）、窄控件、用户空闲、
 * 观察模式下只识别不执行。任何一条不满足都返回 null。
 */
object ExtraRules {

    /** 节点树的纯数据快照，只保留判定需要的最小字段。 */
    data class Node(
        val text: String,
        val desc: String,
        val id: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val clickable: Boolean = false,
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    enum class Kind(val label: String) {
        TEEN_POPUP("青少年模式弹窗"),
        WECHAT_ORIGINAL("微信查看原图"),
    }

    data class Hit(val kind: Kind, val index: Int, val label: String)

    data class Flags(
        val teenPopup: Boolean = false,
        val wechatOriginal: Boolean = false,
    )

    /** 圆形倒计时是否参与内置强信号；由 SkipAdService.syncEngine 跟随开关热更新。 */
    @Volatile
    var roundEnabled: Boolean = true

    const val WECHAT_PKG = "com.tencent.mm"

    /** 青少年模式提示语：同屏必须出现其中一个，才允许点关闭按钮。 */
    private val TEEN_HINT = listOf(
        "青少年模式", "未成年人模式", "青少年守护", "未成年人保护", "青少年保护",
    )

    /**
     * 弹窗的「我知道了」类按钮。这是完整白名单：刻意不含「开启 / 进入 / 设置」
     * 这类会改用户设置的词，所以不需要再加一层关键词黑名单 —— 早先的
     * `contains("开启")` 反而把「暂不开启」「暂不设置」这两个安全的拒绝按钮一起拦掉了。
     */
    private val TEEN_DISMISS = setOf(
        "我知道了", "我知道啦", "知道了", "好的", "好", "关闭", "跳过",
        "暂不开启", "以后再说", "暂不设置", "稍后再说", "取消",
    )

    /** 倒计时资源的最后一段：B 站的 `count_down`、各家的 `splash_countdown` 等。 */
    private val COUNTDOWN_ID = listOf(
        "count_down", "countdown", "skip_time", "ad_time", "splash_time",
        "time_skip", "ad_countdown", "skip_countdown", "tv_skip", "splash_skip",
    )

    /** 纯数字倒计时文本：`3` / `3s` / `3 秒` / `5"`，不含「跳过」二字。 */
    private val PURE_COUNT = Regex("""^\d{1,2}\s*[sS秒"']?$""")

    /** 微信「查看原图」按钮，允许后面跟 `（2.3MB）` 这类体积说明。 */
    private val ORIGINAL_BTN = Regex("""^查看原图[\s（(]*[\d.]*\s*[KMGT]?B?[）)]?$""")

    /**
     * 在整棵节点树里找唯一一个附加动作目标。
     *
     * @param pkg 活动窗口真实包名（以根节点为准，不信事件里的）。
     * @param nodes 与 `Ref` 顺序一致，返回的 [Hit.index] 可直接回查节点。
     * @return 命中则返回目标；否则 null。一次最多返回一个，避免连点。
     */
    fun match(
        pkg: String,
        nodes: List<Node>,
        screenW: Int,
        screenH: Int,
        flags: Flags,
    ): Hit? {
        if (nodes.isEmpty() || screenW <= 0 || screenH <= 0) return null
        if (flags.teenPopup) {
            val h = matchTeen(nodes, screenW)
            if (h != null) return h
        }
        if (flags.wechatOriginal && pkg == WECHAT_PKG) {
            val h = matchOriginal(nodes, screenW)
            if (h != null) return h
        }
        return null
    }

    private fun matchTeen(nodes: List<Node>, screenW: Int): Hit? {
        // 必须有「青少年模式」这类提示语同屏，否则「我知道了」在普通对话框里遍地都是。
        val hasHint = nodes.any { n ->
            TEEN_HINT.any { h -> n.text.contains(h) || n.desc.contains(h) }
        }
        if (!hasHint) return null
        val cands = ArrayList<Pair<Int, Node>>(2)
        for ((i, n) in nodes.withIndex()) {
            val s = n.text.ifEmpty { n.desc }.trim()
            if (s.isEmpty() || s.length > 8) continue
            if (n.width >= screenW * 0.45f) continue
            if (!TEEN_DISMISS.contains(s)) continue
            cands.add(i to n)
            if (cands.size > 1) return null // 两个就放弃，宁可不点
        }
        val (i, n) = cands.singleOrNull() ?: return null
        return Hit(Kind.TEEN_POPUP, i, n.text.ifEmpty { n.desc })
    }

    private fun matchOriginal(nodes: List<Node>, screenW: Int): Hit? {
        val cands = ArrayList<Pair<Int, Node>>(2)
        for ((i, n) in nodes.withIndex()) {
            val s = n.text.ifEmpty { n.desc }.trim()
            if (s.length > 20 || n.width >= screenW * 0.45f) continue
            if (!ORIGINAL_BTN.matches(s)) continue
            cands.add(i to n)
            if (cands.size > 1) return null
        }
        val (i, n) = cands.singleOrNull() ?: return null
        return Hit(Kind.WECHAT_ORIGINAL, i, n.text.ifEmpty { n.desc })
    }

    /**
     * 圆形倒计时跳过按钮：开屏角落里一个近正方形的小时钟，文本常只有 `3` 或
     * `3s`，也可能带 `count_down` 这类资源名。现有 `BARE_SKIP` 只认「跳过 N」，
     * 纯数字的圆环认不出来 —— 这里补的是那一类，不是自绘坐标点击。
     *
     * 四个条件同时成立才算：开屏角落、够小、近正方形、有倒计时语义且可点。
     */
    fun isRoundCountdown(n: Node, screenW: Int, screenH: Int): Boolean {
        if (!roundEnabled) return false
        if (screenW <= 0 || screenH <= 0) return false
        val w = n.width
        val h = n.height
        if (w <= 0 || h <= 0) return false
        if (w > screenW * 0.16f || h > screenH * 0.10f) return false
        val ratio = w.toFloat() / h.toFloat()
        if (ratio < 0.55f || ratio > 1.85f) return false
        val nearLeft = n.right < screenW * 0.30f
        val nearRight = n.left > screenW * 0.70f
        val nearTop = n.top < screenH * 0.30f
        val nearBottom = n.bottom > screenH * 0.78f
        if (!((nearLeft || nearRight) && (nearTop || nearBottom))) return false

        val seg = n.id.substringAfterLast('/').lowercase()
        val idHint = COUNTDOWN_ID.any { seg.contains(it) }
        val s = n.text.ifEmpty { n.desc }.trim()
        val textHint = PURE_COUNT.matches(s) || s == "跳过" || s == "skip"
        // 纯数字太容易是正文里的序号，必须同时有可点击性或倒计时资源名兜底。
        if (!idHint && !(textHint && n.clickable)) return false
        return idHint || textHint
    }
}
