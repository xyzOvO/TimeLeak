package com.chengliu.app.core

import java.util.Locale

/** 归一化一条域名规则；不是域名就丢掉。 */
fun normalizeDomain(raw: String): String? {
    var s = raw.trim().lowercase(Locale.ROOT)
    if (s.isEmpty()) return null
    if (s.startsWith("||")) s = s.removePrefix("||")
    if (s.endsWith("^")) s = s.dropLast(1)
    s = s.trimEnd('.').trim()
    while (s.startsWith("*.")) s = s.removePrefix("*.")
    s = s.trimStart('.')
    if (s.length < 3 || s.length > 253) return null
    if (!s.contains('.')) return null
    if (s.contains('/') || s.contains(':') || s.contains(' ') || s.contains('=')) return null
    if (!s.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }) return null
    // 纯 IP 不当域名规则
    if (s.all { it.isDigit() || it == '.' }) return null
    return s
}

/** 规则从哪来。界面上要能说清「是内置规则拦的，还是你自己加的」。 */
enum class RuleSource(val label: String) {
    BUILTIN("内置规则"),
    CUSTOM("手动拦截"),
    SUBSCRIPTION("导入订阅"),
    UNKNOWN("未知来源"),
}

/** 命中结果：命中的那条规则 + 它的来源。 */
data class Verdict(val rule: String, val source: RuleSource)

/**
 * 把常见订阅格式抽成域名集合。支持的来源：
 * - hosts：`0.0.0.0 域名` / `127.0.0.1 域名` / `::1 域名`
 * - AdGuard / AdBlock：`||域名^`（可带 `$option`）
 * - Clash / Subconverter：`DOMAIN-SUFFIX,域名,策略` / `DOMAIN,域名` / `DOMAIN-KEYWORD,关键词`
 * - Clash `payload:` 风格的 YAML 列表：`- '域名'` / `- "域名"` / `- 域名`
 * - 纯裸域名（一行一个）
 *
 * 跳过的：注释（`# ! ; [ @` 开头）、`PROCESS-NAME / IP-CIDR / GEOIP / SRC-IP / URL-REGEX`
 * 等非域名规则、以及 `DOMAIN-KEYWORD` 里没有点的关键词（避免把「ad」这类词当域名误拦）。
 */
object RuleParser {

    /**
     * 一次解析出「拦截」与「放行」两张表。
     *
     * `@@||domain^` 是 AdGuard 系的例外写法，含义是「这条不要拦」。分成两张表
     * 才能让内置档位自带的 110 条误拦修正真正生效——只认拦截行的话，强档会把
     * 不少正常站点一起干掉。
     */
    fun parseBoth(text: String): ParsedRules {
        if (text.isBlank()) return ParsedRules(emptyList(), emptyList())
        val blocked = ArrayList<String>()
        val allowed = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val isAllow = line.startsWith("@@")
            val d = toDomain(if (isAllow) line.substring(2) else line) ?: continue
            if (isAllow) allowed.add(d) else blocked.add(d)
        }
        return ParsedRules(blocked, allowed)
    }

    fun parse(text: String): List<String> = parseBoth(text).blocked

    /** 单行 → 域名；不是域名规则就返回 null。 */
    fun toDomain(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val first = s[0]
        if (first == '#' || first == '!' || first == ';' || first == '[' || first == '@') return null

        // Clash YAML payload 列表：`- 'domain'` / `- "domain"` / `- domain`
        if (s.startsWith("- ")) {
            s = s.substring(2).trim().trim('\'', '"').trim()
        }

        // Clash / Surge 规则集：DOMAIN-SUFFIX,host,REJECT / DOMAIN,host
        if (s.startsWith("DOMAIN", ignoreCase = true)) {
            val parts = s.split(',')
            if (parts.size < 2) return null
            return normalizeDomain(parts[1].trim().trim('\'', '"'))
        }
        // 非域名的 Clash/Surge 规则类型，整条跳过
        if (s.startsWith("PROCESS-NAME", ignoreCase = true) ||
            s.startsWith("IP-CIDR", ignoreCase = true) ||
            s.startsWith("GEOIP", ignoreCase = true) ||
            s.startsWith("SRC-IP", ignoreCase = true) ||
            s.startsWith("URL-REGEX", ignoreCase = true) ||
            s.startsWith("IP-ASN", ignoreCase = true) ||
            s.startsWith("USER-AGENT", ignoreCase = true) ||
            s.startsWith("AND", ignoreCase = true) ||
            s.startsWith("OR", ignoreCase = true) ||
            s.startsWith("NOT", ignoreCase = true)
        ) return null

        if (s.startsWith("||")) {
            val cut = s.indexOf('^').let { if (it > 0) it else s.length }
            s = s.substring(2, cut)
        } else if (s.startsWith("0.0.0.0 ") || s.startsWith("127.0.0.1 ") ||
            s.startsWith("::1 ") || s.startsWith(":: ")
        ) {
            val parts = s.split(Regex("\\s+"))
            if (parts.size < 2) return null
            s = parts[1]
        } else if (s.contains(' ')) {
            return null
        }
        return normalizeDomain(s)
    }
}

/** 解析结果：拦什么、放什么，一次读文件就分好。 */
data class ParsedRules(val blocked: List<String>, val allowed: List<String>)

/**
 * 后缀匹配：从最具体的子域逐级向上，同级白名单优先于黑名单。
 * 用 HashSet + 剥标签，规则量在几万条以内时比引入 Trie 依赖更划算。
 *
 * 三份名单分开存，是为了让界面能说清「这条是内置规则拦的，还是你导进来的订阅拦的」。
 */
class RuleEngine {
    @Volatile
    private var builtin: Set<String> = emptySet()

    @Volatile
    private var custom: Set<String> = emptySet()

    @Volatile
    private var subscription: Set<String> = emptySet()

    @Volatile
    private var allowed: Set<String> = emptySet()

    fun replace(
        builtinDomains: Collection<String>,
        customDomains: Collection<String>,
        subscriptionDomains: Collection<String>,
        allowlist: Collection<String>,
    ) {
        builtin = builtinDomains.mapNotNull { normalizeDomain(it) }.toHashSet()
        custom = customDomains.mapNotNull { normalizeDomain(it) }.toHashSet()
        subscription = subscriptionDomains.mapNotNull { normalizeDomain(it) }.toHashSet()
        allowed = allowlist.mapNotNull { normalizeDomain(it) }.toHashSet()
    }

    fun decide(name: String): Verdict? {
        val d = normalizeDomain(name) ?: return null
        var cursor: String? = d
        while (cursor != null) {
            if (allowed.contains(cursor)) return null
            if (builtin.contains(cursor)) return Verdict(cursor, RuleSource.BUILTIN)
            if (custom.contains(cursor)) return Verdict(cursor, RuleSource.CUSTOM)
            if (subscription.contains(cursor)) return Verdict(cursor, RuleSource.SUBSCRIPTION)
            val dot = cursor.indexOf('.')
            if (dot < 0) break
            val next = cursor.substring(dot + 1)
            // 别把 com / cn / net 这种公共后缀当规则
            if (!next.contains('.')) break
            cursor = next
        }
        return null
    }

    fun isBlocked(name: String): Boolean = decide(name) != null

    fun count(): Int = builtin.size + custom.size + subscription.size
}
