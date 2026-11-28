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
    private const val KIND_AD_RULES = AdRulesCodec.KIND

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
        // 订阅地址同样是「会被程序主动请求的 URL」：只收 https，和界面里的校验保持一致。
        setOf("subscriptions") { p.subscriptions = it.filter { u -> u.startsWith("https://") }.toSet() }
        setOf("skipSubs") { p.skipSubs = it }
        setOf("subWhitelist") { p.subWhitelist = it.filter { u -> u.startsWith("https://") }.toSet() }
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
        // 导入完成后必须让服务侧重读配置：否则界面显示已改，隧道里跑的还是旧规则，
        // 直到用户再动一次开关才会生效（这种「看起来生效了其实没有」最难查）。
        runCatching { LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD) }
        return "已恢复 $n 项配置，已通知 VPN 重载；订阅正文、拦截记录与运行统计未包含在备份中"
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

    // ---------------- 只导出 / 导入广告规则 ----------------

    /**
     * 只搬「广告跳过规则」：自定义规则正文 + 订阅地址。
     *
     * 刻意不导出订阅正文缓存：那是从远端下载的第三方内容，体积可能上兆，
     * 换机后点一次「立即更新」就能重新拉回来，没必要塞进分享文件里。
     * 也不含应用策略、DNS 配置和任何记录。
     */
    fun exportAdRules(ctx: Context): String {
        val p = AppPrefs(ctx)
        val o = JSONObject()
        o.put("schema", SCHEMA)
        o.put("app", APP)
        o.put("kind", KIND_AD_RULES)
        o.put("at", System.currentTimeMillis())
        o.put("customRules", p.customRules)
        o.put("skipSubs", JSONArray(p.skipSubs.toList()))
        return o.toString(2)
    }

    /**
     * 导入广告规则：字段白名单，订阅地址只收 https://，规则正文限长。
     * 未知字段一律忽略，不执行任何反射，也不合并进配置备份。
     */
    fun importAdRules(ctx: Context, text: String): String {
        val o = runCatching { JSONObject(text) }
            .getOrElse { throw IllegalArgumentException("不是合法的 JSON") }
        // 只把 org.json 读出的原始值交给纯逻辑层校验，判断不留在桩方法这一侧。
        val subsRaw = ArrayList<String>()
        o.optJSONArray("skipSubs")?.let { a ->
            for (i in 0 until minOf(a.length(), AdRulesCodec.MAX_SUBS * 4)) {
                subsRaw.add(a.optString(i, ""))
            }
        }
        val parsed = AdRulesCodec.parse(
            AdRulesCodec.Raw(
                text = text,
                schema = o.optInt("schema", -1),
                app = o.optString("app"),
                kind = o.optString("kind"),
                rules = o.optString("customRules", ""),
                hasRules = o.has("customRules"),
                subs = subsRaw,
                hasSubs = o.has("skipSubs"),
            ),
        )
        val p = AppPrefs(ctx)
        // 导入是替换语义：字段存在才写，缺字段的保留用户当前值。
        if (parsed.rulesPresent) p.customRules = parsed.rules
        val subMsg = parsed.subs?.let {
            p.skipSubs = it
            "订阅源 ${it.size} 个"
        } ?: "订阅源保持不变"
        val ruleMsg = if (parsed.rulesPresent) "自定义规则 ${parsed.rules.length} 字" else "自定义规则保持不变"
        return "已导入：$ruleMsg、$subMsg；订阅正文请点「立即更新」重新拉取"
    }
}
