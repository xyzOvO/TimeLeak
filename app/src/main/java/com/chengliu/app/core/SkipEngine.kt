package com.chengliu.app.core

import android.graphics.Rect

/**
 * 开屏广告 / 弹窗跳过 / 摇一摇落地页 的匹配引擎。
 *
 * 三层规则来源，全部在本机匹配，不联网、不收集界面内容：
 *  1. 内置知识：跨应用的保守文本兜底（"跳过""Skip"…），加上广告 SDK 的
 *     控件 id 特征（穿山甲 / 优量汇 / 快手 / 百度 …，见 [SkipSources]）。
 *     这一层不需要任何外部 App、也不需要订阅，装完就有。
 *  2. 自定义规则：`{"keywords":["关闭广告"]}`、`popup_rules`。
 *     语法兼容主流跳过工具，用户从别处看到的规则可以直接粘进来，
 *     但这不是必需品 —— 只是给愿意折腾的人留的口子。
 *  3. 在线订阅：结构化解析 GKD 格式 json5，按 `apps[].id`（包名）分组。
 *
 * 设计取舍：宁可漏点，不可误点。所有泛化匹配（内置词、全局订阅组）只在
 * 「窗口刚切换」的开屏时间窗内生效；带包名的规则才长期生效。
 *
 * 线程模型：规则解析可能耗时几百毫秒到数秒，绝对不能压在主线程（无障碍
 * 回调就在主线程，压住就是 ANR）。所以解析结果装进不可变的 [Snapshot]，
 * 解析完一次性换引用；读方只读当前引用，永远不会看到半成品。
 */
object SkipEngine {

    // ---------------- 匹配原语 ----------------

    enum class Target { TEXT, DESC, ID, CLASS, ANY }
    enum class Op { EQ, CONTAINS, PREFIX, SUFFIX, REGEX }
    enum class Action { CLICK, BACK }

    data class Clause(val target: Target, val op: Op, val value: String) {
        fun matches(v: String): Boolean {
            if (v.isEmpty()) return false
            return when (op) {
                Op.EQ -> v.equals(value, true)
                Op.CONTAINS -> v.contains(value, true)
                Op.PREFIX -> v.startsWith(value, true)
                Op.SUFFIX -> v.endsWith(value, true)
                Op.REGEX -> runCatching {
                    Regex(value, RegexOption.IGNORE_CASE).containsMatchIn(v)
                }.getOrDefault(false)
            }
        }
    }

    /** 一个规格 = 多个「或」分支，每个分支内是「且」条件。 */
    data class Spec(val alts: List<List<Clause>>) {
        fun hit(text: String, desc: String, id: String, cls: String = ""): Boolean = alts.any { alt ->
            alt.isNotEmpty() && alt.all { c ->
                when (c.target) {
                    Target.TEXT -> c.matches(text)
                    Target.DESC -> c.matches(desc)
                    // GKD 的 vid 是 "tv_skip"，系统给的是 "com.xx:id/tv_skip"，只比最后一段
                    Target.ID -> c.matches(id.substringAfterLast('/').substringAfterLast(':')) ||
                        c.matches(id)
                    Target.CLASS -> c.matches(cls)
                    Target.ANY -> c.matches(text) || c.matches(desc) || c.matches(id)
                }
            }
        }
    }

    data class PopupRule(val trigger: Spec, val action: Spec?, val back: Boolean)

    private data class GkdRule(
        val specs: List<Spec>,
        val excludes: List<Spec>,
        val activityIds: Set<String>,
        val action: Action,
    )

    private data class GkdGroup(
        val pkg: String?,          // null = globalGroups，仅开屏窗口生效
        val matchTimeMs: Long,
        val rules: List<GkdRule>,
        /**
         * 订阅里标了 `enable: false` 的组。GKD 的语义是「默认不启用，等用户自己开」，
         * 这批规则数量占绝大多数，但作者没验证过每一个的场景，误触概率更高。
         * 折中：保留，但只在前 [STRICT_MAX_MS] 内认 —— 开屏本来就在头几秒，
         * 正文里的误点则被挡在时间窗外。
         */
        val strict: Boolean = false,
    )

    /** 一次解析的完整结果，构建后不再修改。 */
    private class Snapshot(
        val groups: List<GkdGroup>,
        val keywords: List<Spec>,
        val bounds: List<Rect>,
        val popups: List<PopupRule>,
        val rules: Int,
    ) {
        companion object {
            val EMPTY = Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), 0)
        }
    }

    private class Builder {
        val groups = ArrayList<GkdGroup>(600)
        val keywords = ArrayList<Spec>()
        val bounds = ArrayList<Rect>()
        val popups = ArrayList<PopupRule>()
        var rules = 0
        var apps = 0
        var groupsRaw = 0
        var ruleRaw = 0
    }

    // ---------------- 状态 ----------------

    /** 内置知识（文本兜底 + SDK 控件特征）是否参与匹配。 */
    @Volatile
    var builtin: Boolean = true

    @Volatile
    private var snap: Snapshot = Snapshot.EMPTY

    @Volatile
    private var loadedKey = Int.MIN_VALUE

    /**
     * 订阅缓存或自定义规则变化时重新解析。
     * 用 hashCode 做变更检测，避免每次无障碍事件都重新解析几百 KB 文本。
     * 可以放心在 UI 线程之外调用；同一个 key 重复调用会直接返回。
     */
    fun ensureLoaded(subCache: String, customRules: String) {
        val key = 31 * subCache.hashCode() + customRules.hashCode()
        if (key == loadedKey) return
        synchronized(this) {
            if (key == loadedKey) return
            val b = Builder()
            if (subCache.isNotBlank()) parseGkd(subCache.take(12_000_000), b)
            if (customRules.isNotBlank()) parseCustom(customRules.take(1_000_000), b)
            android.util.Log.i(
                "SkipAd",
                "解析明细: apps=${b.apps} 原始组=${b.groupsRaw} 可用组=${b.groups.size} " +
                    "原始规则=${b.ruleRaw} 可用规则=${b.rules}",
            )
            snap = Snapshot(b.groups, b.keywords, b.bounds, b.popups, b.rules)
            loadedKey = key
        }
    }

    fun groupCount(): Int = snap.groups.size
    fun ruleCount(): Int = snap.rules
    fun popupCount(): Int = snap.popups.size
    fun keywordCount(): Int = snap.keywords.size
    fun boundsCount(): Int = snap.bounds.size

    fun popups(): List<PopupRule> = snap.popups

    /** 坐标规则（自定义 keywords 里写的 "x1,y1,x2,y2"）。 */
    fun boundsHit(r: Rect): Boolean {
        val list = snap.bounds
        if (list.isEmpty() || r.width() <= 0 || r.height() <= 0) return false
        val cx = r.exactCenterX()
        val cy = r.exactCenterY()
        return list.any { cx >= it.left && cx <= it.right && cy >= it.top && cy <= it.bottom }
    }

    // ---------------- 第一层：内置知识 ----------------

    /** 上下文明确的强信号，几乎不可能是正文。 */
    private val STRONG = listOf(
        "跳过广告", "点击跳过", "跳过此页", "跳过开屏", "广告跳过", "跳过提示",
        "跳过此广告", "跳过该广告", "关闭广告", "关闭此广告", "关闭该广告",
        "关闭推广", "跳过推广", "skip ad", "skip ads", "skip this ad", "close ad",
    )

    /**
     * 只认「跳过」本身，或带倒计时：`跳过`、`跳过 3`、`跳过3s`、`跳过(3)`、`skip 5`、
     * 以及倒计时归零后的 `跳过 0s`、`跳过 >`。
     * 后面跟任何别的字都不算 —— 正文里的「跳过本章」「跳过验证」都是用户
     * 真正想点的功能，点掉就是事故。
     */
    private val BARE_SKIP = Regex(
        """^(跳过|skip)\s*[（(]?\s*\d*\s*[s秒]?\s*[)）]?\s*[>»›→]?$""",
    )

    /** 否定式 / 明显是正文的措辞，命中就不该点。 */
    private val NEG = listOf(
        "不可", "无法", "不能", "跳过片", "跳过剧", "跳过章节",
        "跳过本次", "跳过此步", "跳过此章", "跳过片头", "跳过片尾",
        "跳过设置向导", "跳过此流程", "不再提示",
    )

    private val ID_SKIP = Regex("(^|[_-])(skip|skipad|adskip|splashskip|tvskip|btnskip)($|[_-])")

    fun builtinHit(text: String, desc: String, id: String): Boolean {
        if (!builtin) return false
        for (raw in arrayOf(text, desc)) {
            val s = raw.trim()
            if (s.isEmpty() || s.length > 12) continue
            val low = s.lowercase()
            if (NEG.any { low.contains(it) }) continue
            if (STRONG.any { low.contains(it) }) return true
            if (BARE_SKIP.matches(low)) return true
            if (enSkip(low)) return true
        }
        if (id.isNotEmpty()) {
            // 广告 SDK 的皮肤资源名：跨应用通用，且不会出现在业务界面上
            if (SkipSources.sdkHit(id)) return true
            val seg = id.substringAfterLast('/').lowercase()
            if (seg.isNotEmpty() && ID_SKIP.containsMatchIn(seg)) return true
        }
        return false
    }

    private fun enSkip(low: String): Boolean =
        low == "skip" || low.startsWith("skip ") || low.startsWith("skip|") ||
            low.endsWith(" skip") || Regex("^skip\\s*\\d").containsMatchIn(low)

    // ---------------- 订阅 / 自定义 规则命中 ----------------

    /**
     * 按包名 / Activity 作用域的规则匹配。
     * @param windowAgeMs 距离当前窗口（Activity）切换已经过去多久。GKD 自己也用
     *   `matchTime` 限定「进入页面后多久内可以点」，超出就认为不是开屏场景。
     */
    fun scopedHit(
        pkg: String?,
        activity: String?,
        text: String,
        desc: String,
        id: String,
        windowAgeMs: Long,
        cls: String = "",
    ): Action? {
        val list = groupsFor(pkg)
        if (list.isEmpty()) return null
        val act = activity?.substringAfterLast('.') ?: ""
        for (g in list) {
            // 订阅里的 matchTime 可以写得很大（有的写 60 秒），必须再夹一道：
            // 开屏只发生在前十几秒，超出的时间窗只会让服务在正文里继续点。
            val limit = when {
                g.pkg == null -> minOf(g.matchTimeMs, GLOBAL_MAX_MS)
                g.strict -> minOf(g.matchTimeMs, STRICT_MAX_MS)
                else -> minOf(g.matchTimeMs, MAX_MATCH_MS)
            }
            if (windowAgeMs > limit) continue
            for (r in g.rules) {
                if (r.activityIds.isNotEmpty() && act.isNotEmpty() &&
                    r.activityIds.none { it.equals(act, true) }
                ) continue
                if (r.excludes.any { it.hit(text, desc, id, cls) }) continue
                if (r.specs.any { it.hit(text, desc, id, cls) }) return r.action
            }
        }
        return null
    }

    /**
     * 命中的是不是**全局组**（订阅里没写包名的通用规则）。
     * 全局规则只在前几秒生效，评分时会额外扣分；单独判一次是为了不把
     * 「带包名作用域」和「全局兜底」混成同一种证据。
     */
    fun globalHit(
        text: String,
        desc: String,
        id: String,
        windowAgeMs: Long,
        cls: String = "",
    ): Boolean {
        if (windowAgeMs > GLOBAL_MAX_MS) return false
        for (g in snap.groups) {
            if (g.pkg != null) continue
            if (windowAgeMs > minOf(g.matchTimeMs, GLOBAL_MAX_MS)) continue
            for (r in g.rules) {
                if (r.excludes.any { it.hit(text, desc, id, cls) }) continue
                if (r.specs.any { it.hit(text, desc, id, cls) }) return true
            }
        }
        return false
    }

    /** 按包名过滤后的规则组缓存：几百组订阅里通常只有 1~3 组和当前应用有关。 */
    private var cachePkg: String? = null
    private var cacheSnap: Snapshot? = null
    private var cacheGroups: List<GkdGroup> = emptyList()

    private fun groupsFor(pkg: String?): List<GkdGroup> {
        val s = snap
        if (cacheSnap === s && cachePkg == pkg) return cacheGroups
        val list = ArrayList<GkdGroup>(4)
        for (g in s.groups) {
            if (g.pkg == null || (pkg != null && pkg.equals(g.pkg, true))) list.add(g)
        }
        cachePkg = pkg
        cacheSnap = s
        cacheGroups = list
        return list
    }

    /** 用户点击的目标绝不能是广告 CTA（那是「打开广告」而不是「跳过广告」）。 */
    fun isSafeTarget(text: String, desc: String): Boolean {
        val s = (text.ifEmpty { desc }).trim()
        if (s.isEmpty()) return true
        if (s.length > 12) return false
        val low = s.lowercase()
        if (CTA.any { low.contains(it) }) return false
        if (DENY.any { low.contains(it) }) return false
        return true
    }

    private val CTA = listOf(
        "立即下载", "下载", "安装", "体验", "打开", "查看详情", "了解更多", "详情",
        "领取", "购买", "加入购物车", "进入游戏", "开始游戏", "预约", "参与", "报名",
        "同意并继续", "同意并接受", "授权", "开启", "去开通", "升级", "更新",
        // 付费 / 下载广告、激励视频的常见 CTA
        "立即打开", "马上抢", "去购买", "下单", "立即开通", "立即领取", "免费领",
        "看视频", "去领取", "打开应用", "进入小程序", "咨询客服", "立即咨询",
        "去查看", "去看看", "立即体验", "立即安装", "获取", "抢购", "立即预约",
    )

    /**
     * 「跳过」在正文里有大量正当用途，这些一个都不能点。
     * 订阅规则用的是 `text*="跳过"`（包含匹配），如果不在这里统一拦一道，
     * 小说 / 视频 / 引导流程会被点得稀碎。
     */
    private val DENY = listOf(
        "跳过片", "跳过剧", "跳过集", "跳过章", "跳过本", "跳过下",
        "跳过任务", "跳过验证", "跳过登录", "跳过注册", "跳过绑定",
        "跳过教程", "跳过引导", "跳过练习", "跳过测试", "跳过步骤",
        "跳过设置", "跳过问卷", "跳过答题", "跳过检查", "跳过更新",
        "跳过安装", "跳过备份", "跳过恢复", "跳过同步", "跳过下载",
        "跳过本次", "跳过此步", "跳过此章", "跳过片头", "跳过片尾", "跳过此流程",
        // 「关闭 XX」在正文里几乎都是用户自己的设置，不能当成广告关闭
        "关闭通知", "关闭提醒", "关闭引导", "关闭教程", "关闭说明", "关闭设置",
        "关闭定位", "关闭推送", "关闭自动", "关闭省电", "关闭夜间", "关闭护眼",
        "关闭推荐", "关闭浮层", "关闭提示", "不感兴趣", "减少此类",
    )

    /** 自定义 keywords 规则（全局，仅开屏窗口生效）。 */
    fun customHit(text: String, desc: String, id: String, cls: String = ""): Boolean =
        snap.keywords.any { it.hit(text, desc, id, cls) }

    // ---------------- 广告类型识别 ----------------

    /**
     * 激励视频：这里的「关闭」意味着放弃奖励，用户点不点是他自己的决定。
     * 匹配范围放到 text + desc 一起看，宁可多判成 REWARDED 少点一下。
     */
    private val REWARDED_HINTS = listOf(
        "激励", "领取奖励", "获得奖励", "领取金币", "领取福利", "看视频得",
        "看视频领", "看完可得", "再看", "继续观看", "视频奖励", "免费领",
        "reward", "earn reward", "watch to",
    )

    /** 付费 / 下载广告的 CTA：点下去是「打开广告」，方向与跳过相反。 */
    private val PAID_HINTS = listOf(
        "立即下载", "立即安装", "立即购买", "立即抢", "去购买", "下单",
        "查看详情", "了解详情", "进入游戏", "开始游戏", "立即咨询",
        "打开应用", "马上抢", "抢购", "advertisement", "sponsored",
    )

    /** 信息流卡片上的小标签；必须配合「宽控件」才判 FEED，避免误伤开屏跳过按钮。 */
    private val FEED_LABELS = listOf("广告", "推广", "赞助", "sponsored", "advertisement")

    /** 非开屏窗口里唯一认可的推广弹窗关闭文案，比 EXPLICIT_AD_CLOSE 更窄。 */
    private val POPUP_CLOSE_TEXT = listOf(
        "关闭广告", "关闭此广告", "关闭该广告", "广告关闭", "关闭推广",
        "推广关闭", "关闭插屏", "close ad", "close advertisement",
    )

    /** 应用内弹窗轮的入场券：只有这些信号才允许在非开屏窗口点击。 */
    fun explicitPopupClose(text: String, desc: String, id: String): Boolean {
        if (SkipSources.sdkCloseHit(id)) return true
        for (raw in arrayOf(text, desc)) {
            val s = raw.trim().lowercase()
            if (s.isEmpty() || s.length > 12) continue
            if (POPUP_CLOSE_TEXT.any { s.contains(it) }) return true
        }
        return false
    }

    /**
     * 对「候选点击目标」做类型判定。
     *
     * 顺序是故意的：先排除激励视频 / 付费广告 / 信息流这三种绝不能点的，
     * 再看是不是开屏，最后才考虑推广弹窗。任何认不出来的都是 UNKNOWN，
     * 而 UNKNOWN 一律不点 —— 这是「对无法确认的广告不自动点击」的落点。
     *
     * 开屏窗口内，订阅规则 / 自定义关键词 / 用户坐标本身也算有效证据：
     * 它们是否放行由 [SkipPolicy] 的 textGuess / gesture 位决定，类型层不重复拦。
     */
    fun classify(
        text: String,
        desc: String,
        id: String,
        cls: String,
        bounds: Rect,
        screenW: Int,
        screenH: Int,
        inSplash: Boolean,
        reason: HitReason,
    ): AdKind {
        val s = (text.ifEmpty { desc }).trim().lowercase()
        val both = "$text $desc".trim().lowercase()

        if (REWARDED_HINTS.any { both.contains(it) }) return AdKind.REWARDED
        if (PAID_HINTS.any { s.contains(it) }) return AdKind.PAID

        val wide = screenW > 0 && bounds.width() >= screenW * 0.45f
        if (wide && FEED_LABELS.any { s.contains(it) } &&
            !SkipSources.explicitAdClose(text, desc, id)
        ) return AdKind.FEED

        val corner = SkipSources.closeCornerHit(
            (text.ifEmpty { desc }).trim(),
            bounds.left, bounds.top, bounds.right, bounds.bottom, screenW, screenH,
        )
        if (inSplash) {
            val strong = SkipSources.sdkHit(id) ||
                SkipSources.sdkCloseHit(id) ||
                builtinHit(text, desc, "") ||
                SkipSources.explicitAdClose(text, desc, id) || corner
            if (strong || reason == HitReason.SUBSCRIPTION ||
                reason == HitReason.TEXT_GUESS || reason == HitReason.COORDINATE
            ) return AdKind.SPLASH
        }

        if (explicitPopupClose(text, desc, id)) return AdKind.POPUP
        return AdKind.UNKNOWN
    }

    // ---------------- GKD 解析 ----------------

    private fun parseGkd(src: String, b: Builder) {
        val root = Json5.parse(src) ?: return
        // 多个订阅文件拼在一起时，parse 返回「多个顶层值」的列表
        if (root is List<*>) {
            for (doc in root) walkGkd(doc as? Map<*, *> ?: continue, b)
        } else {
            walkGkd(root as? Map<*, *> ?: return, b)
        }
    }

    private fun walkGkd(node: Map<*, *>, b: Builder) {
        (node["globalGroups"] as? List<*>)?.forEach { g ->
            val gm = g as? Map<*, *> ?: return@forEach
            b.groupsRaw++
            val rules = parseRules(gm, b) ?: return@forEach
            val mt = (gm["matchTime"] as? Double)?.toLong() ?: 9000L
            b.groups.add(GkdGroup(null, mt, rules, gm["enable"] == false))
        }
        for (a in (node["apps"] as? List<*>) ?: emptyList<Any?>()) {
            val am = a as? Map<*, *> ?: continue
            b.apps++
            val pkg = (am["id"] as? String)?.trim() ?: continue
            if (!PKG.matches(pkg)) continue
            for (g in (am["groups"] as? List<*>) ?: emptyList<Any?>()) {
                val gm = g as? Map<*, *> ?: continue
                b.groupsRaw++
                val rules = parseRules(gm, b) ?: continue
                val mt = (gm["matchTime"] as? Double)?.toLong() ?: 9000L
                b.groups.add(GkdGroup(pkg, mt, rules, gm["enable"] == false))
            }
        }
    }

    /** GKD 新版的 `matches` / `activityIds` 是字符串，旧版是数组，两种都得认。 */
    private fun strings(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is List<*> -> v.mapNotNull { it as? String }
        else -> emptyList()
    }

    private fun parseRules(groupMap: Map<*, *>, b: Builder): List<GkdRule>? {
        val list = groupMap["rules"] as? List<*> ?: return null
        val out = ArrayList<GkdRule>()
        for (item in list) {
            val rm = item as? Map<*, *> ?: continue
            // 单条规则显式 enable:false 是作者明确标注「这条别用」，直接跳过；
            // 组级的 enable:false 只是 GKD 的「默认不启用」开关（官方订阅里几乎
            // 每组都是），不能整组丢掉，否则规则库就废了 —— 见 GkdGroup.strict。
            if (rm["enable"] == false) continue
            b.ruleRaw++
            val excludes = ArrayList<Spec>()
            for (s in strings(rm["excludeMatches"])) gkdSpecs(s, excludes)
            val specs = ArrayList<Spec>()
            for (s in strings(rm["matches"])) specs.addAll(gkdSpecs(s, excludes))
            if (specs.isEmpty()) continue
            val acts = HashSet<String>()
            for (s in strings(rm["activityIds"])) {
                s.trim().takeIf { it.isNotEmpty() }?.let { acts.add(it.substringAfterLast('.')) }
            }
            val actionRaw = (rm["action"] as? String)?.lowercase() ?: ""
            val action = if (actionRaw == "back" || actionRaw == "pressback") Action.BACK else Action.CLICK
            out.add(GkdRule(specs, excludes, acts, action))
            if (++b.rules > MAX_RULES) break
        }
        return out.ifEmpty { null }
    }

    /**
     * 把 GKD 选择器里可用的条件抽出来。
     *
     * GKD 的选择器描述的是**节点层级**（`A > B`、`A + B`），我们只能拿单个节点
     * 去比，所以不能把所有条件 AND 起来 —— `[name=X] > [name=Y]` 这种要求同一个
     * 节点既是 X 又是 Y，永远不可能命中。
     *
     * 折中：每个 `||` 分支只保留**最具体的一个**条件，优先级 vid > text > desc。
     * vid 最准（SDK 皮肤资源名），text 次之；`name` 是类名，单独出现时太泛，
     * 直接丢掉，宁可漏点也不要在正文里乱点。
     */
    private fun gkdSpecs(selector: String, excludes: MutableList<Spec>): List<Spec> {
        val out = ArrayList<Spec>()
        for (part in splitTop(selector, "||")) {
            val byId = ArrayList<Clause>()
            val byText = ArrayList<Clause>()
            val byDesc = ArrayList<Clause>()
            val byName = ArrayList<Clause>()
            for (m in PRED.findAll(part)) {
                val attr = m.groupValues[1].lowercase()
                val opRaw = m.groupValues[2]
                val value = m.groupValues[3].ifEmpty { m.groupValues[4] }.trim()
                if (value.isEmpty()) continue
                val target = when (attr) {
                    "text" -> Target.TEXT
                    "desc" -> Target.DESC
                    "vid", "id" -> Target.ID
                    "name" -> Target.CLASS
                    else -> continue
                }
                if (opRaw == "!=") {
                    // != 是排除条件，和「命中条件」不是一回事
                    excludes.add(Spec(listOf(listOf(Clause(target, Op.CONTAINS, value)))))
                    continue
                }
                val op = when (opRaw) {
                    "=" -> Op.EQ
                    "*=" -> Op.CONTAINS
                    "^=" -> Op.PREFIX
                    "$=" -> Op.SUFFIX
                    "~=" -> Op.REGEX
                    else -> null
                } ?: continue
                if (op == Op.REGEX && value.length > 60) continue
                val c = Clause(target, op, value)
                // 同一个分支内、同一属性的多个条件是「且」关系，必须全部保留。
                //
                // 之前这里用「后者覆盖前者」，GKD 全局开屏规则里的
                //   vid*="count" && vid*="down" && vid!*="download"
                // 只剩下 vid*="down"，结果饿了么首页的 drop_down_image
                // （下拉箭头）被当成跳过按钮点了。
                when (target) {
                    Target.ID -> if (byId.size < 3) byId.add(c)
                    Target.TEXT -> if (byText.size < 3) byText.add(c)
                    Target.DESC -> if (byDesc.size < 3) byDesc.add(c)
                    else -> if (byName.size < 3) byName.add(c)
                }
            }
            // 不同属性之间取「最具体的一档」，不跨属性 AND：
            // `[text="A"] > [name="B"]` 描述的是两个节点，压到一个节点上
            // 会永远不命中。类名单独用太泛，只有带广告特征词时才兜底。
            val clauses = when {
                byId.isNotEmpty() -> byId
                byText.isNotEmpty() -> byText
                byDesc.isNotEmpty() -> byDesc
                else -> byName.filter { CLASS_HINT.containsMatchIn(it.value) }
            }
            if (clauses.isEmpty()) continue
            out.add(Spec(listOf(clauses)))
        }
        return out
    }


    // ---------------- 自定义规则解析 ----------------

    private fun parseCustom(src: String, b: Builder) {
        val root = Json5.parse(src) ?: return
        walkCustom(root, b)
    }

    private fun walkCustom(node: Any?, b: Builder) {
        when (node) {
            is Map<*, *> -> {
                readKeywordList(node["keywords"], b)
                readKeywordList(node["keywords_append"], b)
                (node["popup_rules"] as? List<*>)?.forEach { r ->
                    val rm = r as? Map<*, *> ?: return@forEach
                    val id = (rm["id"] as? String)?.trim() ?: return@forEach
                    if (id.isEmpty()) return@forEach
                    val trigger = customSpec(id) ?: return@forEach
                    val action = (rm["action"] as? String)?.trim() ?: ""
                    when {
                        action.isEmpty() -> {}
                        action.equals("back", true) || action == "返回" || action == "返回键" ->
                            b.popups.add(PopupRule(trigger, null, true))
                        else -> customSpec(action)?.let { b.popups.add(PopupRule(trigger, it, false)) }
                    }
                }
                node.values.forEach { v -> if (v is Map<*, *> || v is List<*>) walkCustom(v, b) }
            }
            is List<*> -> node.forEach { v -> if (v is Map<*, *> || v is List<*>) walkCustom(v, b) }
        }
    }

    private fun readKeywordList(v: Any?, b: Builder) {
        val list = v as? List<*> ?: return
        for (item in list) {
            val raw = (item as? String)?.trim() ?: continue
            if (raw.isEmpty()) continue
            val rect = parseBounds(raw)
            if (rect != null) {
                b.bounds.add(rect)
                continue
            }
            customSpec(raw)?.let { b.keywords.add(it) }
        }
    }

    /** "900,160,1170,250" → Rect */
    private fun parseBounds(raw: String): Rect? {
        val parts = raw.split(',')
        if (parts.size != 4) return null
        val nums = parts.map { it.trim().toIntOrNull() ?: return null }
        val r = Rect(nums[0], nums[1], nums[2], nums[3])
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    /** 单条匹配串：支持 `+` 前缀、`-` 后缀、`=` 全等、`&` 且。 */
    private fun customSpec(raw: String): Spec? {
        val t = raw.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (t.isEmpty()) return null
        val parts = t.split('&').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val clauses = parts.map { p ->
            when {
                p.startsWith("=") -> Clause(Target.ANY, Op.EQ, p.substring(1))
                p.startsWith("+") -> Clause(Target.ANY, Op.PREFIX, p.substring(1))
                p.startsWith("-") -> Clause(Target.ANY, Op.SUFFIX, p.substring(1))
                else -> Clause(Target.ANY, Op.CONTAINS, p)
            }
        }
        return Spec(listOf(clauses))
    }

    // ---------------- 工具 ----------------

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** 全局组（无包名）最长只在前 4.5 秒内生效，避免在应用内误点。 */
    private const val GLOBAL_MAX_MS = 4500L

    /** 带包名的组也不能超过这个时间窗，防止订阅里的长 matchTime 在正文里继续点。 */
    private const val MAX_MATCH_MS = 12_000L

    /** 订阅里默认不启用的组，只在开屏最前 5 秒内认。 */
    private const val STRICT_MAX_MS = 5_000L

    /** 规则总数上限，防止畸形订阅把内存吃光。600+ App 的合集大约 2 千多条。 */
    private const val MAX_RULES = 30_000

    /** 类名里出现这些词，说明它本身就是广告 / 跳过控件，可以兜底当条件。 */
    private val CLASS_HINT = Regex("skip|close|jump|splash|advert", RegexOption.IGNORE_CASE)

    private val PRED = Regex(
        """(text|desc|name|vid|id)\s*(!=|\*=|\^=|${'$'}=|~=|=)\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE,
    )

    /** 按分隔符切分，但跳过引号内部（`text="a||b"` 不该被切开）。 */
    private fun splitTop(s: String, sep: String): List<String> {
        val out = ArrayList<String>()
        var quote = '\u0000'
        var last = 0
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != '\u0000') {
                if (c == quote) quote = '\u0000'
            } else if (c == '"' || c == '\'') {
                quote = c
            } else if (c == sep[0] && s.startsWith(sep, i)) {
                out.add(s.substring(last, i))
                i += sep.length
                last = i
                continue
            }
            i++
        }
        out.add(s.substring(last))
        return out
    }
}
