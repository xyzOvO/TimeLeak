package com.chengliu.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地配置备份与恢复：导出成一份纯 JSON，由用户自己选保存位置。
 *
 * 刻意**不导出**：订阅正文、拦截记录、观察记录、运行统计、恢复预算。
 * 前两者可能含界面文字 / 浏览痕迹，后两者没有备份价值；备份只带配置。
 * 导入走严格字段白名单，不执行任何反射，也不接受未知字段。
 */
object BackupRestore {
    const val SCHEMA = 1
    private const val APP = "com.chengliu.app"
    private const val KIND_APP_RULES = "app-rules"

    fun export(ctx: Context): String {
        val p = AppPrefs(ctx)
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("app", APP)
        o.put("at", System.currentTimeMillis())

        o.put("builtinEnabled", p.builtinEnabled)
        o.put("ruleTier", p.ruleTier)
        o.put("customBlocked", JSONArray(p.customBlocked.toList()))
        o.put("customAllowed", JSONArray(p.customAllowed.toList()))
        o.put("subscriptions", JSONArray(p.subscriptions.toList()))
        o.put("skipSubs", JSONArray(p.skipSubs.toList()))
        o.put("subWhitelist", JSONArray(p.subWhitelist.toList()))
        o.put("customRules", p.customRules)
        o.put("appPolicies", JSONObject(p.appPolicies))
        o.put("appSkipOff", JSONArray(p.appSkipOff.toList()))
        o.put("protectedApps", JSONArray(p.protectedApps.toList()))

        o.put("upstreamMode", p.upstreamMode)
        o.put("upstreamIp", p.upstreamIp)
        o.put("dohUrl", p.dohUrl)
        o.put("leakGuard", p.leakGuard)
        o.put("stripEcs", p.stripEcs)
        o.put("blockBypass", p.blockBypass)
        o.put("leakTargetsText", p.leakTargetsText)

        o.put("blockLog", p.blockLog)
        o.put("themeMode", p.themeMode)
        o.put("skipAdEnabled", p.skipAdEnabled)
        o.put("skipBuiltin", p.skipBuiltin)
        o.put("skipAutoUpdate", p.skipAutoUpdate)
        o.put("shakeBlock", p.shakeBlock)
        o.put("observeMode", p.observeMode)
        o.put("bootRestore", p.bootRestore)
        o.put("healthNotify", p.healthNotify)
        o.put("subAutoApply", p.subAutoApply)
        return o.toString(2)
    }

    /** @return 导入摘要；失败时抛出 IllegalArgumentException。 */
    fun import(ctx: Context, text: String): String {
        if (text.length > 2_000_000) throw IllegalArgumentException("备份文件过大")
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("不是合法的 JSON 备份") }
        if (o.optInt("schema", -1) != SCHEMA) throw IllegalArgumentException("备份版本不兼容")
        if (o.optString("app") != APP) throw IllegalArgumentException("这不是 Time Leak 的备份")
        val p = AppPrefs(ctx)
        var n = 0

        fun bool(k: String, set: (Boolean) -> Unit) {
            if (!o.has(k)) return
            set(o.optBoolean(k)); n++
        }
        fun str(k: String, set: (String) -> Unit) {
            if (!o.has(k)) return
            val v = o.optString(k, "").take(20_000)
            set(v); n++
        }
        fun setOf(k: String, set: (Set<String>) -> Unit) {
            if (!o.has(k)) return
            val a = o.optJSONArray(k) ?: return
            val s = LinkedHashSet<String>()
            for (i in 0 until minOf(a.length(), 5000)) {
                val v = a.optString(i, "").trim()
                if (v.isNotEmpty() && v.length < 500) s.add(v)
            }
            set(s); n++
        }

        bool("builtinEnabled") { p.builtinEnabled = it }
        str("ruleTier") { if (it in listOf("weak", "medium", "strong")) p.ruleTier = it }
        setOf("customBlocked") { p.customBlocked = it }
        setOf("customAllowed") { p.customAllowed = it }
        setOf("subscriptions") { p.subscriptions = it }
        setOf("skipSubs") { p.skipSubs = it }
        setOf("subWhitelist") { p.subWhitelist = it }
        str("customRules") { p.customRules = it }
        if (o.has("appPolicies")) {
            val m = LinkedHashMap<String, String>()
            val a = o.optJSONObject("appPolicies") ?: JSONObject()
            a.keys().forEach { k ->
                val v = a.optString(k, "")
                if (k.length < 200 && PolicyMode.of(v) != null) m[k] = v
            }
            p.appPolicies = m
            n++
        }
        setOf("appSkipOff") { p.appSkipOff = it }
        setOf("protectedApps") { p.protectedApps = it }
        str("upstreamMode") { if (it in listOf("system", "ip", "doh")) p.upstreamMode = it }
        str("upstreamIp") { p.upstreamIp = it }
        str("dohUrl") { if (it.startsWith("https://")) p.dohUrl = it }
        bool("leakGuard") { p.leakGuard = it }
        bool("stripEcs") { p.stripEcs = it }
        bool("blockBypass") { p.blockBypass = it }
        str("leakTargetsText") { p.leakTargetsText = it }
        bool("blockLog") { p.blockLog = it }
        str("themeMode") { if (it in listOf("system", "light", "dark")) p.themeMode = it }
        bool("skipAdEnabled") { p.skipAdEnabled = it }
        bool("skipBuiltin") { p.skipBuiltin = it }
        bool("skipAutoUpdate") { p.skipAutoUpdate = it }
        bool("shakeBlock") { p.shakeBlock = it }
        bool("observeMode") { p.observeMode = it }
        bool("bootRestore") { p.bootRestore = it }
        bool("healthNotify") { p.healthNotify = it }
        bool("subAutoApply") { p.subAutoApply = it }
        return "已恢复 $n 项配置；订阅正文、拦截记录与运行统计未包含在备份中"
    }

    // ---------------- 只导出 / 导入应用规则 ----------------

    /**
     * 单独导出「应用级规则」：策略档位 + 关闭列表 + 保护名单。
     *
     * 换手机时不必带着 DNS / 上游配置一起搬，也方便把一份调好的
     * 应用清单发给别人对照。文件里只有包名和档位，没有界面内容。
     */
    fun exportAppRules(ctx: Context): String {
        val p = AppPrefs(ctx)
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("app", APP)
        o.put("kind", KIND_APP_RULES)
        o.put("at", System.currentTimeMillis())
        o.put("appPolicies", JSONObject(p.appPolicies))
        o.put("skipOff", JSONArray(p.appSkipOff.toList()))
        o.put("protected", JSONArray(p.protectedApps.toList()))
        return o.toString(2)
    }

    /** 导入应用规则：字段白名单，未知包名与未知档位一律丢弃。 */
    fun importAppRules(ctx: Context, text: String): String {
        if (text.length > 2_000_000) throw IllegalArgumentException("文件过大")
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("不是合法的 JSON") }
        if (o.optInt("schema", -1) != SCHEMA) throw IllegalArgumentException("配置版本不兼容")
        if (o.optString("app") != APP) throw IllegalArgumentException("这不是 Time Leak 的配置")
        if (o.optString("kind") != KIND_APP_RULES) throw IllegalArgumentException("这不是应用规则配置")

        val p = AppPrefs(ctx)
        val m = LinkedHashMap<String, String>()
        o.optJSONObject("appPolicies")?.let { a ->
            a.keys().forEach { k ->
                val v = a.optString(k, "")
                if (k.length < 200 && PolicyMode.of(v) != null) m[k] = v
            }
        }
        fun readSet(name: String): Set<String> {
            val a = o.optJSONArray(name) ?: return emptySet()
            val s = LinkedHashSet<String>()
            for (i in 0 until minOf(a.length(), 5000)) {
                val v = a.optString(i, "").trim()
                if (v.isNotEmpty() && v.length < 200) s.add(v)
            }
            return s
        }
        p.appPolicies = m
        p.appSkipOff = readSet("skipOff")
        p.protectedApps = readSet("protected")
        return "已导入 ${m.size} 条策略、${p.appSkipOff.size} 个关闭项、${p.protectedApps.size} 个保护应用"
    }
}
