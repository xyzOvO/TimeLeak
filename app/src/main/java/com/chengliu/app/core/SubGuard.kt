package com.chengliu.app.core

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

/**
 * 订阅安全网关。
 *
 * 立场：**不能因为它是 GKD 或其它公开订阅，就无条件信任后续所有内容。**
 * 任何一次更新都要经过「来源可信 -> 指纹可核 -> 差异可看 -> 异常可拦 -> 可回滚」。
 * 默认不自动应用：先给用户看差异，确认后才替换正在生效的规则。
 */
data class SubIssue(val blocking: Boolean, val text: String) {
    val label: String get() = if (blocking) "阻断" else "提醒"
}

data class SubDiff(
    val before: Int = 0,
    val after: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val addedSamples: List<String> = emptyList(),
    val removedSamples: List<String> = emptyList(),
)

data class SubCheck(
    val url: String,
    val host: String,
    val sha256: String,
    val shortHash: String,
    val ruleCount: Int,
    val diff: SubDiff,
    val issues: List<SubIssue>,
    val blocked: Boolean,
    val trusted: Boolean,
) {
    val sourceTag: String get() = "$host · $shortHash"
}

data class SubMeta(val sha256: String, val at: Long, val count: Int, val host: String)

object SubGuard {

    fun hostOf(url: String): String = runCatching {
        java.net.URI(url).host ?: url.take(40)
    }.getOrDefault(url.take(40))

    fun isTrusted(prefs: AppPrefs, url: String): Boolean =
        prefs.subWhitelist.contains(url)

    fun trust(prefs: AppPrefs, url: String) {
        prefs.subWhitelist = prefs.subWhitelist + url
    }

    fun untrust(prefs: AppPrefs, url: String) {
        prefs.subWhitelist = prefs.subWhitelist - url
        prefs.subGoodMap = prefs.subGoodMap - url
        prefs.subMeta = prefs.subMeta - url
    }

    fun metaOf(prefs: AppPrefs, url: String): SubMeta? {
        val raw = prefs.subMeta[url] ?: return null
        return runCatching {
            val o = JSONObject(raw)
            SubMeta(
                o.optString("sha", ""), o.optLong("at", 0L),
                o.optInt("count", 0), o.optString("host", hostOf(url)),
            )
        }.getOrNull()
    }

    private fun sha(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** 规则条数：DNS 名单按行解析，GKD 按 matches 字段粗统计。 */
    fun countRules(content: String): Int {
        if (content.isBlank()) return 0
        return if (content.trimStart().startsWith("{")) {
            // GKD 订阅是 JSON5：`matches: '...'` 与 `"matches": "..."` 两种都要认，
            // 否则默认订阅会被误判成「一条规则都没有」而整个拦下。
            Regex("""["']?matches["']?\s*:""").findAll(content).count()
        } else {
            val p = RuleParser.parseBoth(content)
            p.blocked.size + p.allowed.size
        }
    }

    /** 按行做差异；超长订阅只看前 2 万行，避免把 800KB 全铺进内存。 */
    private fun diff(old: String, new: String, before: Int, after: Int): SubDiff {
        fun lines(s: String): Set<String> = s.lineSequence()
            .take(20_000)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") && !it.startsWith("#") }
            .toHashSet()
        val a = lines(old)
        val b = lines(new)
        val added = (b - a).toList()
        val removed = (a - b).toList()
        return SubDiff(
            before = before,
            after = after,
            added = added.size,
            removed = removed.size,
            addedSamples = added.take(5).map { it.take(80) },
            removedSamples = removed.take(5).map { it.take(80) },
        )
    }

    /**
     * 检查一次下载结果。只是「看」，不写入任何缓存，也不改变正在生效的规则。
     */
    fun inspect(prefs: AppPrefs, url: String, content: String, previous: String): SubCheck {
        val hash = sha(content)
        val before = if (previous.isBlank()) 0 else countRules(previous)
        val after = countRules(content)
        val issues = ArrayList<SubIssue>()
        val low = content.lowercase()

        fun block(t: String) { issues.add(SubIssue(true, t)) }
        fun warn(t: String) { issues.add(SubIssue(false, t)) }

        // 内容级异常：公开订阅可能被投毒，脚本 / 本地文件协议一律不接受。
        if (low.contains("javascript:")) block("含 javascript: 脚本片段")
        if (low.contains("<script")) block("含 <script> 标签")
        if (Regex("""\beval\s*\(""").containsMatchIn(low)) block("含 eval( 调用")
        if (low.contains("file://")) block("含 file:// 本地文件引用")
        if (content.lineSequence().any { it.length > 2000 }) block("存在超过 2000 字符的超长行")
        if (after == 0) block("没有解析出任何规则，可能是错误页或空响应")

        // 放行规则暴增：等于把整份拦截名单悄悄放开，是典型的恶意/误配置更新。
        val allowBefore = previous.lineSequence().count { it.trim().startsWith("@@") }
        val allowAfter = content.lineSequence().count { it.trim().startsWith("@@") }
        if (allowAfter - allowBefore > 50) {
            block("放行规则比上一版多 ${allowAfter - allowBefore} 条，疑似整表放开")
        }

        if (before > 0) {
            if (after < before * 0.5) {
                block("规则条数从 $before 骤降到 $after（超过 50%），可能是残缺响应")
            } else if (after > before * 5) {
                warn("规则条数从 $before 增到 $after，请确认订阅没换内容")
            }
        }
        if (!content.trimStart().startsWith("{") &&
            !content.lineSequence().take(20).any {
                val l = it.trim()
                l.startsWith("||") || l.startsWith("0.0.0.0 ") || l.startsWith("#") ||
                    l.startsWith("!") || l.startsWith("[") || l.contains(".")
            }
        ) warn("内容不像域名名单，请先预览差异")

        val host = hostOf(url)
        return SubCheck(
            url = url,
            host = host,
            sha256 = hash,
            shortHash = hash.take(8),
            ruleCount = after,
            diff = diff(previous, content, before, after),
            issues = issues,
            blocked = issues.any { it.blocking },
            trusted = isTrusted(prefs, url),
        )
    }

    /**
     * 用户确认后调用：把这一版记为 last-known-good，并写入来源指纹。
     * 真正替换生效缓存由调用方完成（DNS 名单与 GKD 订阅存在不同的缓存键）。
     */
    fun commit(ctx: Context, url: String, content: String, check: SubCheck) {
        val prefs = AppPrefs(ctx)
        prefs.subGoodMap = prefs.subGoodMap + (url to content)
        val o = JSONObject()
        o.put("sha", check.sha256)
        o.put("at", System.currentTimeMillis())
        o.put("count", check.ruleCount)
        o.put("host", check.host)
        prefs.subMeta = prefs.subMeta + (url to o.toString())
        prefs.subError = ""
    }

    /** 回滚到最近一次「用户确认过」的正文。返回 null 表示没有可回滚的版本。 */
    fun rollback(ctx: Context, url: String): String? =
        AppPrefs(ctx).subGoodMap[url]

    fun goodCount(ctx: Context): Int = AppPrefs(ctx).subGoodMap.size

    /** 完整性校验：拿当前缓存与记录的指纹对比，用于隐私审计页。 */
    fun verifyCurrent(ctx: Context, url: String, content: String): Boolean {
        val meta = metaOf(AppPrefs(ctx), url) ?: return false
        return sha(content) == meta.sha256
    }
}
