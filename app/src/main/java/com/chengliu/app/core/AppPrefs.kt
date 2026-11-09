package com.chengliu.app.core

import android.content.Context
import android.content.SharedPreferences

/** 全部配置集中在一个 SharedPreferences 文件里，不建数据库、不落查询日志。 */
class AppPrefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("chengliu_prefs", Context.MODE_PRIVATE)

    var builtinEnabled: Boolean
        get() = sp.getBoolean(KEY_BUILTIN, true)
        set(v) = sp.edit().putBoolean(KEY_BUILTIN, v).apply()

    /** 内置规则强度：weak / medium / strong，三档都随 APK 一起装着。 */
    var ruleTier: String
        get() = sp.getString(KEY_RULE_TIER, "medium") ?: "medium"
        set(v) = sp.edit().putString(KEY_RULE_TIER, v).apply()

    var customBlocked: Set<String>
        get() = sp.getStringSet(KEY_BLOCKED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_BLOCKED, v.toHashSet()).apply()

    var customAllowed: Set<String>
        get() = sp.getStringSet(KEY_ALLOWED, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_ALLOWED, v.toHashSet()).apply()

    var subscriptions: Set<String>
        get() = sp.getStringSet(KEY_SUBS, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_SUBS, v.toHashSet()).apply()

    /** 订阅正文缓存，仅存规则文本，不含任何查询记录。 */
    var subscriptionCache: String
        get() = sp.getString(KEY_SUB_CACHE, "") ?: ""
        set(v) = sp.edit().putString(KEY_SUB_CACHE, v).apply()

    /** system = 系统探测；ip = 用户指定 IP；doh = 加密上游 */
    var upstreamMode: String
        get() = sp.getString(KEY_MODE, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_MODE, v).apply()

    /** 开启后忽略上面的模式，一律走 DoH；连不上就失败，不回退明文。 */
    var leakGuard: Boolean
        get() = sp.getBoolean(KEY_LEAK, true)
        set(v) = sp.edit().putBoolean(KEY_LEAK, v).apply()

    /** 转发前剥掉 EDNS Client Subnet，避免把网段一起送给上游。 */
    var stripEcs: Boolean
        get() = sp.getBoolean(KEY_ECS, true)
        set(v) = sp.edit().putBoolean(KEY_ECS, v).apply()

    /** 把常见公共 DNS 地址纳入隧道，接管应用自带的明文查询。 */
    var blockBypass: Boolean
        get() = sp.getBoolean(KEY_BYPASS, true)
        set(v) = sp.edit().putBoolean(KEY_BYPASS, v).apply()

    /** 用户手写的接管目标原文：IPv4、网段、域名、规则地址，一行一条。 */
    var leakTargetsText: String
        get() = sp.getString(KEY_LEAK_TEXT, "") ?: ""
        set(v) = sp.edit().putString(KEY_LEAK_TEXT, v).apply()

    /** 上次解析出的可用路由。存下来是为了启动时不用重新联网。 */
    var leakTargetIps: Set<String>
        get() = sp.getStringSet(KEY_LEAK_IPS, emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet(KEY_LEAK_IPS, v.toHashSet()).apply()

    /** 上次更新的结果描述，显示在界面上。 */
    var leakTargetNote: String
        get() = sp.getString(KEY_LEAK_NOTE, "") ?: ""
        set(v) = sp.edit().putString(KEY_LEAK_NOTE, v).apply()

    var upstreamIp: String
        get() = sp.getString(KEY_IP, "") ?: ""
        set(v) = sp.edit().putString(KEY_IP, v.trim()).apply()

    var dohUrl: String
        get() = sp.getString(KEY_DOH, DEFAULT_DOH) ?: DEFAULT_DOH
        set(v) = sp.edit().putString(KEY_DOH, v.trim()).apply()

    /** 在内存里保留拦截归属（服务名 + 示例域名），用来解释「谁被拦了」。关掉即不记录。 */
    var blockLog: Boolean
        get() = sp.getBoolean(KEY_BLOCK_LOG, true)
        set(v) = sp.edit().putBoolean(KEY_BLOCK_LOG, v).apply()

    /** system = 跟随系统；light = 浅色；dark = 深色 */
    var themeMode: String
        get() = sp.getString(KEY_THEME, "system") ?: "system"
        set(v) = sp.edit().putString(KEY_THEME, v).apply()

    /** 是否启用自动跳过开屏广告（无障碍服务授权 + 此项同时为 true 才工作）。 */
    var skipAdEnabled: Boolean
        get() = sp.getBoolean("skip_ad_enabled", false)
        set(v) = sp.edit().putBoolean("skip_ad_enabled", v).apply()

    /** 内置通用跳过词是否参与匹配（跨应用兜底）。 */
    var skipBuiltin: Boolean
        get() = sp.getBoolean("skip_builtin", true)
        set(v) = sp.edit().putBoolean("skip_builtin", v).apply()

    /**
     * 跳过规则订阅地址（GKD 格式链接）。
     * 没存过就用内置的默认源 —— 装上就有规则，不用自己去 GitHub 找。
     */
    var skipSubs: Set<String>
        get() = if (sp.contains(KEY_SKIP_SUBS)) {
            sp.getStringSet(KEY_SKIP_SUBS, emptySet())?.toSet() ?: emptySet()
        } else {
            SkipSources.DEFAULT_SUBS.toSet()
        }
        set(v) = sp.edit().putStringSet(KEY_SKIP_SUBS, v.toHashSet()).apply()

    /** 订阅正文缓存（已下载的 JSON5 文本），由 SkipEngine 解析。 */
    var skipSubCache: String
        get() = sp.getString("skip_sub_cache", "") ?: ""
        set(v) = sp.edit().putString("skip_sub_cache", v).apply()

    /** 上次成功拉取订阅的时间戳，0 表示从未拉过。 */
    var skipSubAt: Long
        get() = sp.getLong(KEY_SKIP_SUB_AT, 0L)
        set(v) = sp.edit().putLong(KEY_SKIP_SUB_AT, v).apply()

    /** 允许每隔一天静默更新一次订阅。 */
    var skipAutoUpdate: Boolean
        get() = sp.getBoolean(KEY_SKIP_AUTO, true)
        set(v) = sp.edit().putBoolean(KEY_SKIP_AUTO, v).apply()

    /** 该不该自动更新：开关打开且距上次更新超过一天。 */
    fun skipAutoDue(now: Long = System.currentTimeMillis()): Boolean =
        skipAutoUpdate && now - skipSubAt > DAY_MS

    /**
     * 自定义规则文本（keywords / keywords_append / popup_rules）。
     * 旧版本存在 `ljt_rules` 下，这里做一次透明迁移，避免用户白填。
     */
    var customRules: String
        get() = sp.getString(KEY_CUSTOM_RULES, null)
            ?: sp.getString("ljt_rules", "")
            ?: ""
        set(v) = sp.edit().putString(KEY_CUSTOM_RULES, v).apply()

    /** 摇一摇广告拦截：跳到浏览器 / 应用商店 / 电商落地页时自动返回。 */
    var shakeBlock: Boolean
        get() = sp.getBoolean("shake_block", true)
        set(v) = sp.edit().putBoolean("shake_block", v).apply()

    // ---------------- 今日统计（跨天自动归零） ----------------

    var skipToday: Int
        get() = readStat(KEY_STAT_SKIP)
        private set(value) {
            writeStat(KEY_STAT_SKIP, value)
        }

    var shakeToday: Int
        get() = readStat(KEY_STAT_SHAKE)
        private set(value) {
            writeStat(KEY_STAT_SHAKE, value)
        }

    fun bumpSkip() {
        skipToday = skipToday + 1
    }

    fun bumpShake() {
        shakeToday = shakeToday + 1
    }

    /** 手动把今天的计数清零。 */
    fun resetSkipStats() {
        sp.edit()
            .putString(KEY_STAT_DATE, todayKey())
            .putInt(KEY_STAT_SKIP, 0)
            .putInt(KEY_STAT_SHAKE, 0)
            .apply()
    }

    private fun readStat(key: String): Int =
        if (sp.getString(KEY_STAT_DATE, "") == todayKey()) sp.getInt(key, 0) else 0

    private fun writeStat(key: String, v: Int) {
        val k = todayKey()
        val e = sp.edit()
        if (sp.getString(KEY_STAT_DATE, "") != k) {
            e.putString(KEY_STAT_DATE, k)
            e.putInt(KEY_STAT_SKIP, 0)
            e.putInt(KEY_STAT_SHAKE, 0)
        }
        e.putInt(key, v)
        e.apply()
    }

    private fun todayKey(): String =
        java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    companion object {
        const val DEFAULT_DOH = "https://dns.alidns.com/dns-query"
        private const val KEY_BUILTIN = "builtin"
        private const val KEY_RULE_TIER = "rule_tier"
        private const val KEY_BLOCKED = "blocked"
        private const val KEY_ALLOWED = "allowed"
        private const val KEY_SUBS = "subs"
        private const val KEY_SUB_CACHE = "sub_cache"
        private const val KEY_SKIP_SUBS = "skip_subs"
        private const val KEY_SKIP_SUB_AT = "skip_sub_at"
        private const val KEY_SKIP_AUTO = "skip_auto_update"
        private const val KEY_CUSTOM_RULES = "custom_rules"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val KEY_MODE = "mode"
        private const val KEY_IP = "ip"
        private const val KEY_DOH = "doh"
        private const val KEY_THEME = "theme"
        private const val KEY_LEAK = "leak_guard"
        private const val KEY_ECS = "strip_ecs"
        private const val KEY_BYPASS = "block_bypass"
        private const val KEY_LEAK_TEXT = "leak_targets_text"
        private const val KEY_LEAK_IPS = "leak_targets_ips"
        private const val KEY_LEAK_NOTE = "leak_targets_note"
        private const val KEY_BLOCK_LOG = "block_log"
        private const val KEY_STAT_DATE = "stat_date"
        private const val KEY_STAT_SKIP = "stat_skip"
        private const val KEY_STAT_SHAKE = "stat_shake"
    }
}
