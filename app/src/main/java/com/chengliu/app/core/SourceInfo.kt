package com.chengliu.app.core

import android.content.Context
import com.chengliu.app.data.RuleTier

/**
 * 「这条规则是哪份名单里的」——来源不写死在代码里。
 *
 * 每次规则档位切换、内置开关变化、订阅增删/刷新，LocalVpnService.reloadRules()
 * 都会调一次 refresh()：重新读取 assets 规则文件头（!Title / !Description /
 * !Source / !Rules）与当前订阅列表，让页面底部与之后新拦下的记录用上新来源。
 * 以后换一版 anti-AD 规则文件，来源与版本日期会自己跟着变，无需改代码。
 *
 * 只读 assets 前十几行和 SharedPreferences：不联网、不收集任何查询域名。
 */
object SourceInfo {

    private class Head(
        val upstream: String,
        val version: String,
        val rules: Int,
    )

    @Volatile private var head: Head? = null
    @Volatile private var subs: List<String> = emptyList()
    @Volatile private var subAt: Long = 0L
    @Volatile private var builtinOn: Boolean = true
    @Volatile private var tierLabel: String = "中档"

    /** 重新取一次来源信息，并让拦截记录页重新发布一次（底部当前规则随之刷新）。 */
    fun refresh(ctx: Context, prefs: AppPrefs) {
        val tier = RuleTier.of(prefs.ruleTier)
        tierLabel = tier.label
        builtinOn = prefs.builtinEnabled
        head = readHead(ctx, tier)
        subs = prefs.subscriptions.sorted()
        subAt = prefs.subUpdatedAt
        BlockLog.republish()
    }

    /** 给拦截记录卡片用的「来自」一行。 */
    fun originOf(source: RuleSource): String = when (source) {
        RuleSource.BUILTIN -> {
            val h = head
            if (h == null) "内置规则 · $tierLabel"
            else "内置规则 · $tierLabel · ${h.upstream}" +
                (if (h.version.isNotEmpty()) " ${h.version}" else "") +
                (if (h.rules > 0) " · ${h.rules} 条" else "")
        }
        RuleSource.SUBSCRIPTION -> when {
            subs.isEmpty() -> "导入订阅 · 当前没有启用任何订阅源"
            subs.size == 1 -> "订阅名单 · ${shortSub(subs[0])}"
            // 规则引擎把多个源合并成一张表，无法确指是哪一个，别装作知道
            else -> "订阅名单 · 共 ${subs.size} 个来源（${shortSub(subs[0])} 等）"
        }
        RuleSource.CUSTOM -> "手动拦截 · 你自己添加的规则"
        RuleSource.UNKNOWN -> "来源不明 · 建议核对后再决定是否放行"
    }

    /** 拦截记录页底部的动态说明，不用写死的覆盖数字糊弄人。 */
    fun summary(): String {
        val h = head
        val builtin = if (!builtinOn) "内置规则已关闭"
        else "内置 $tierLabel" + (h?.let {
            "（${it.upstream}" + (if (it.version.isNotEmpty()) " ${it.version}" else "") +
                (if (it.rules > 0) "，${it.rules} 条" else "") + "）"
        } ?: "")
        val sub = if (subs.isEmpty()) "未启用订阅" else "${subs.size} 个订阅来源"
        val at = if (subAt > 0L) {
            "，最近更新 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date(subAt))
        } else ""
        return "$builtin；$sub$at"
    }

    private fun readHead(ctx: Context, tier: RuleTier): Head? = runCatching {
        ctx.applicationContext.assets.open(tier.asset).bufferedReader().use { r ->
            var source = ""
            var rules = 0
            var i = 0
            while (i++ < 12) {
                val line = r.readLine() ?: break
                when {
                    line.startsWith("!Source:") -> source = line.removePrefix("!Source:").trim()
                    line.startsWith("!Rules:") -> rules = line.removePrefix("!Rules:").trim().toIntOrNull() ?: 0
                }
            }
            // !Source: anti-AD 20261105074937 (https://...) → anti-AD / 2026-11-05
            val stamp = Regex("""\d{14}""").find(source)?.value
            val version = if (stamp != null && stamp.length >= 8) {
                "${stamp.substring(0, 4)}-${stamp.substring(4, 6)}-${stamp.substring(6, 8)}"
            } else ""
            val upstream = source.replace(Regex("""\s*\d{8,}.*$"""), "").trim()
            Head(upstream.ifEmpty { "内置名单" }, version, rules)
        }
    }.getOrNull()

    /** 订阅地址压成一行可读的短名：主机 + 末段文件名。 */
    private fun shortSub(u: String): String = runCatching {
        val uri = java.net.URI(u)
        val fallback = u.take(42)
        val host = uri.host ?: fallback
        val last = uri.path?.trimEnd('/')?.substringAfterLast('/')?.take(24).orEmpty()
        if (last.isNotEmpty() && host != fallback) "$host/…/$last" else host
    }.getOrDefault(u.take(42))
}
