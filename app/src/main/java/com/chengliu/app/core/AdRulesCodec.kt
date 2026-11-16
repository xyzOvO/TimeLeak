package com.chengliu.app.core

/**
 * 广告规则导入文件的校验与清洗 —— 纯 Kotlin、零 Android 依赖。
 *
 * `BackupRestore` 只负责 `org.json` 的读写（那些 API 在 JVM 单测里是空壳桩），
 * 所有「能不能导、导入什么」的判断都放在这里，才能被单测真正覆盖。
 *
 * 两条设计约束：
 *  - **字段白名单**：只认 `customRules` 与 `skipSubs`，订阅地址只收 https；
 *    明文订阅可能被中间人塞进任意点击规则，宁可丢掉也不能放行。
 *  - **缺字段不覆盖**：文件里没有 `skipSubs` 时返回 null，保留用户原有的订阅地址，
 *    避免「只想分享一条自定义规则，结果把对方的订阅清空了」。
 */
object AdRulesCodec {
    const val SCHEMA = 1
    const val APP = "com.chengliu.app"
    const val KIND = "ad-rules"
    const val MAX_TEXT = 1_000_000
    const val MAX_SUBS = 200
    const val MAX_URL = 500

    /** `org.json` 读出来的原始值；Boolean 表示字段是否真的存在（不是默认值）。 */
    data class Raw(
        val text: String,
        val schema: Int,
        val app: String,
        val kind: String,
        val rules: String,
        val hasRules: Boolean,
        val subs: List<String>,
        val hasSubs: Boolean,
    )

    /** [subs] 为 null 表示文件里没有这个字段，调用方不要覆盖现有订阅。 */
    data class Parsed(
        val rules: String,
        val subs: Set<String>?,
        val rulesPresent: Boolean,
    )

    /** @throws IllegalArgumentException 文件不是本应用的广告规则，或没有可导入的内容。 */
    fun parse(raw: Raw): Parsed {
        if (raw.text.length > MAX_TEXT) throw IllegalArgumentException("文件过大")
        if (raw.schema != SCHEMA) throw IllegalArgumentException("规则版本不兼容")
        if (raw.app != APP) throw IllegalArgumentException("这不是 Time Leak 的规则文件")
        if (raw.kind != KIND) throw IllegalArgumentException("这不是广告规则文件（应用规则请用侧栏那页导入）")
        if (!raw.hasRules && !raw.hasSubs) throw IllegalArgumentException("文件里没有可导入的广告规则")
        return Parsed(
            rules = if (raw.hasRules) raw.rules.take(MAX_TEXT) else "",
            subs = if (raw.hasSubs) cleanSubs(raw.subs) else null,
            rulesPresent = raw.hasRules,
        )
    }

    /** 去空白、去重、只留 https、限长、限量；顺序按文件里出现的先后保留。 */
    fun cleanSubs(src: List<String>): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in src) {
            if (out.size >= MAX_SUBS) break
            val u = s.trim()
            if (u.startsWith("https://") && u.length < MAX_URL) out.add(u)
        }
        return out
    }
}
