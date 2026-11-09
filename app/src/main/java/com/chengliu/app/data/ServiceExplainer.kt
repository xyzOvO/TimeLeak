package com.chengliu.app.data

/**
 * 说明表（ServiceCatalog：手工核实 + 随规则版本生成的 GeneratedCatalog）
 * 只收录能公开确认归属的域名，对上强档 9.6 万条规则覆盖率仍然很低。
 * 这里给其余域名一个兜底说明。
 *
 * 原则：**只按域名词根推断，不冒充已确认的事实**。文案里明确写出「推断 /
 * 未核实」，不编造公司名、不猜具体业务，也不联网查询——查询过的域名
 * 依旧只留在内存里，不出这个进程。
 */
object ServiceExplainer {

    data class Guess(
        val name: String,
        val owner: String,
        val kind: ServiceKind,
        val what: String,
        val why: String,
    )

    // 顺序即优先级：归因最具体，广告最泛。短词（<=3）要求整段相等，
    // 否则 download / catalog / head 这类词会被 "ad" / "log" 误伤。
    private val PATTERNS: List<Pair<ServiceKind, List<String>>> = listOf(
        ServiceKind.ATTRIB to listOf(
            "appsflyer", "adjust", "attribution", "attrib", "referrer", "installreferrer",
        ),
        ServiceKind.TELEMETRY to listOf(
            "crash", "bugly", "telemetry", "diagnostic", "exception", "apm",
            "beacon", "monitor", "log", "logs", "logger", "report",
        ),
        ServiceKind.TRACK to listOf(
            "analytics", "analytic", "track", "tracer", "stat", "stats", "statistic",
            "tongji", "collect", "pixel", "event", "measure", "metric", "counter",
            "umeng", "cnzz", "sensors", "growingio",
        ),
        ServiceKind.AD to listOf(
            "ad", "ads", "advert", "adservice", "banner", "splash", "promo",
            "promotion", "union", "adx", "ssp", "dsp", "impression", "mobads", "pangolin",
        ),
    )

    private val KIND_TEXT = mapOf(
        ServiceKind.AD to "广告投放或广告素材地址",
        ServiceKind.TRACK to "访问统计 / 行为追踪地址",
        ServiceKind.ATTRIB to "安装归因（判断从哪条广告装了 App）地址",
        ServiceKind.TELEMETRY to "设备上报 / 崩溃日志地址",
    )

    fun guess(domain: String, rule: String): Guess {
        // 用实际查询的完整域名判断词根。rule 常常是父域，拿它推断会把子域里的
        // -ad / -log / -tracker 这些最关键的信息丢掉，误判成「判断不出」。
        val shown = domain.trim().trimEnd('.').lowercase()
            .ifBlank { rule.trim().trimEnd('.').lowercase() }
        val labels = shown.split('.').flatMap { it.split('-', '_') }.filter { it.isNotEmpty() }
        var kind = ServiceKind.OTHER
        var hit = ""
        outer@ for ((k, words) in PATTERNS) {
            for (w in words) {
                val matched = labels.any { label ->
                    if (w.length <= 3) label == w else label.contains(w)
                }
                if (matched) {
                    kind = k
                    hit = w
                    break@outer
                }
            }
        }
        val what = when {
            kind != ServiceKind.OTHER ->
                "从名称里的「$hit」看，像是${KIND_TEXT[kind]}；这是按域名推断，未核实归属"
            looksRandom(shown) ->
                "随机拼出来的一串字符，公开资料里查不到运营方；这类域名常被批量注册、定期轮换，用来避开拦截名单"
            else ->
                "名称里没有广告或统计词根，也查不到可核实的归属；可能是厂商自有子域，也可能是被名单顺带收进来的内容地址"
        }
        val why = "名单把它列为需要拦截的地址（具体到哪份名单，见卡片里的「命中时来自」）。" +
            "若它其实是正常服务，可把域名加入放行名单；这里的说明只是按名称与形态判断，不代表确认过它的实际行为。"
        return Guess(shown, "按名称推断", kind, what, why)
    }

    /**
     * 形如 3yx95kv3at / 000491b06a 的随机标签：长、少元音、多数字，或整串十六进制。
     * 只用来说明「查不到归属、常见于轮换」，不据此断言它是哪家公司。
     */
    private fun looksRandom(domain: String): Boolean {
        val head = domain.substringBefore('.')
        if (head.length < 8) return false
        val digits = head.count { it.isDigit() }
        val vowels = head.count { it in "aeiou" }
        val hex = head.all { it.isDigit() || it in 'a'..'f' }
        return hex || (digits >= 2 && vowels <= head.length / 5)
    }
}
