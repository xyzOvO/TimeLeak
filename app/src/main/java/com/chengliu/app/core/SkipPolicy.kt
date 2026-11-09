package com.chengliu.app.core

import android.content.Context

/**
 * 广告类型与应用级跳过策略。
 *
 * 两条不可让步的原则：
 *  1. **只有 SPLASH / POPUP 允许自动点击**。信息流广告、激励视频、付费/下载广告
 *     以及任何无法确认的目标，识别出来也只是「不点」的理由，不是点击的入口。
 *     激励视频的「关闭」会让用户丢掉奖励，付费广告的 CTA 点下去是打开广告，
 *     信息流的「×」多半是「不感兴趣」，都不是跳过。
 *  2. **应用级策略优先于任何规则**。银行、支付、政务类默认完全不自动点击；
 *     用户为某个包名指定的档位永远压过预置和类别推断。
 *
 * 这里只做判定，不读取、不上传、不落盘任何界面内容。
 */

// HitReason / AdKind / PolicyMode 已移到 DecisionModel.kt（零 Android 依赖，
// 供 PC 上的离线规则回归复用）。这里只保留策略判定与应用预置表。

data class SkipPolicy(val mode: PolicyMode, val preset: Boolean = true) {
    /** 统一的点击闸门：类型不过、场景不允许、证据太软，任何一种都拒绝。 */
    fun allows(kind: AdKind, reason: HitReason): Boolean {
        if (mode == PolicyMode.DISABLED) return false
        if (!kind.clickable) return false
        if (kind == AdKind.SPLASH && !mode.splash) return false
        if (kind == AdKind.POPUP && !mode.popup) return false
        if (!mode.textGuess && (reason == HitReason.SUBSCRIPTION || reason == HitReason.TEXT_GUESS)) {
            return false
        }
        if (!mode.gesture && reason == HitReason.COORDINATE) return false
        return true
    }
}

/** 界面上展示的预置条目。 */
data class AppPreset(
    val pkg: String,
    val label: String,
    val mode: PolicyMode,
    val note: String,
)

object SkipPolicyStore {

    /** 按用户给的示例表预置；包名写全，避免模糊匹配误伤。 */
    val PRESETS: List<AppPreset> = listOf(
        AppPreset("com.taobao.taobao", "淘宝", PolicyMode.SPLASH_ONLY, "只跳过开屏，购物 / 活动弹窗不碰"),
        AppPreset("com.tmall.wireless", "天猫", PolicyMode.SPLASH_ONLY, "同上"),
        AppPreset("com.jingdong.app.mall", "京东", PolicyMode.SPLASH_ONLY, "同上"),
        AppPreset("com.ss.android.ugc.aweme", "抖音", PolicyMode.STRICT_SPLASH, "只处理明确的开屏跳过按钮"),
        AppPreset("com.ss.android.ugc.aweme.lite", "抖音极速版", PolicyMode.STRICT_SPLASH, "同上"),
        AppPreset("com.ss.android.article.news", "今日头条", PolicyMode.FULL, "允许关闭推广弹窗"),
        AppPreset("com.tencent.news", "腾讯新闻", PolicyMode.FULL, "允许关闭推广弹窗"),
        AppPreset("com.netease.newsreader.activity", "网易新闻", PolicyMode.FULL, "允许关闭推广弹窗"),
        AppPreset("com.eg.android.AlipayGphone", "支付宝", PolicyMode.DISABLED, "金融支付类，默认不自动点击"),
        AppPreset("com.unionpay", "云闪付", PolicyMode.DISABLED, "金融支付类，默认不自动点击"),
        AppPreset("com.chinamworld.main", "建设银行", PolicyMode.DISABLED, "银行类，默认不自动点击"),
        AppPreset("com.icbc", "工商银行", PolicyMode.DISABLED, "银行类，默认不自动点击"),
    )

    private val PRESET_MODES: Map<String, PolicyMode> =
        PRESETS.associate { it.pkg.lowercase() to it.mode }

    private val PRESET_LABELS: Map<String, String> =
        PRESETS.associate { it.pkg to it.label }

    /** 包名里的敏感特征：宁可多禁，也不要在金融 App 里替用户点一下。 */
    private val SENSITIVE_PKG = listOf(
        "bank", "icbc", "ccb", "abchina", "boc.", "bocsoft", "cmbchina", "cmb.pb",
        "unionpay", "alipay", "mbank", "spdb", "citicbank", "cebbank", "pingan",
        "bankcomm", "psbc", "cgbchina", "hxb", "cib", "wallet", "securities", "insurance",
    )

    /** 应用名里的敏感词，覆盖「银行 APP」这类按类别给的规则。 */
    private val SENSITIVE_LABEL = listOf(
        "银行", "支付", "钱包", "证券", "基金", "保险", "理财", "贷款", "信用卡",
        "公积金", "社保", "医保", "税务", "政务", "掌上生活", "云闪付",
    )

    /** 电商与商店：只跳开屏，购物 / 支付流程里的任何控件都不碰。 */
    private val SHOP_PKG = listOf(
        "taobao", "tmall", "jd.", "jingdong", "pinduoduo", "suning", "vipshop",
        "vending", ".market", "appstore", "appgallery",
    )
    private val SHOP_LABEL = listOf("淘宝", "天猫", "京东", "拼多多", "苏宁", "唯品会", "商城")

    private val SHORT_VIDEO_PKG = listOf("aweme", "douyin", "kuaishou", "kuaishou.nebula", "tiktok")

    private val labelCache = HashMap<String, String>()

    /** 每次窗口切换调用一次，低频；用户改策略后下一次窗口切换即生效。 */
    fun forPkg(ctx: Context, pkg: String): SkipPolicy {
        val prefs = AppPrefs(ctx)
        // 保护名单优先于一切：进了名单就不自动点击，连摇一摇回退也不做。
        if (prefs.protectedApps.contains(pkg)) return SkipPolicy(PolicyMode.DISABLED, preset = false)
        // 用户临时关掉这个应用的自动跳过，但策略档位保留着。
        if (prefs.appSkipOff.contains(pkg)) return SkipPolicy(PolicyMode.DISABLED, preset = false)
        return SkipPolicy(baseMode(ctx, pkg), preset = !prefs.appPolicies.containsKey(pkg))
    }

    /**
     * 不含「临时关闭 / 保护名单」的档位：用户覆盖 > 预置表 > 类别推断。
     *
     * 界面回显要用这个——一个被临时关掉的应用不应该显示成「不自动点击」，
     * 那会让用户以为策略被改掉了。
     */
    fun baseMode(ctx: Context, pkg: String): PolicyMode {
        val custom = PolicyMode.of(AppPrefs(ctx).appPolicies[pkg])
        if (custom != null) return custom
        return PRESET_MODES[pkg.lowercase()] ?: guessMode(ctx, pkg)
    }

    /** 界面回显用：这个包名是不是被用户手动关掉 / 保护了。 */
    fun isProtected(ctx: Context, pkg: String): Boolean = AppPrefs(ctx).protectedApps.contains(pkg)

    fun isSkipOff(ctx: Context, pkg: String): Boolean = AppPrefs(ctx).appSkipOff.contains(pkg)

    /** 用户没覆盖过、也没关掉、也没保护时，策略是自动推断出来的。 */
    fun isAuto(ctx: Context, pkg: String): Boolean {
        val prefs = AppPrefs(ctx)
        return !prefs.protectedApps.contains(pkg) &&
            !prefs.appSkipOff.contains(pkg) &&
            PolicyMode.of(prefs.appPolicies[pkg]) == null
    }

    /** 用户显式设置过的档位（用于界面回显）。 */
    fun overrides(ctx: Context): Map<String, String> = AppPrefs(ctx).appPolicies

    fun displayName(ctx: Context, pkg: String): String =
        PRESET_LABELS[pkg] ?: appLabel(ctx, pkg).ifEmpty { pkg }

    private fun guessMode(ctx: Context, pkg: String): PolicyMode {
        val p = pkg.lowercase()
        if (SENSITIVE_PKG.any { p.contains(it) }) return PolicyMode.DISABLED
        val label = appLabel(ctx, pkg)
        if (SENSITIVE_LABEL.any { label.contains(it) }) return PolicyMode.DISABLED
        if (SHOP_PKG.any { p.contains(it) } || SHOP_LABEL.any { label.contains(it) }) {
            return PolicyMode.SPLASH_ONLY
        }
        if (SHORT_VIDEO_PKG.any { p.contains(it) }) return PolicyMode.STRICT_SPLASH
        // 新闻 / 资讯类归到默认档：允许关闭推广弹窗，但仍要求明确广告信号
        return PolicyMode.FULL
    }

    private fun appLabel(ctx: Context, pkg: String): String {
        labelCache[pkg]?.let { return it }
        val s = runCatching {
            ctx.packageManager.getApplicationLabel(
                ctx.packageManager.getApplicationInfo(pkg, 0),
            ).toString()
        }.getOrDefault("")
        labelCache[pkg] = s
        return s
    }
}
