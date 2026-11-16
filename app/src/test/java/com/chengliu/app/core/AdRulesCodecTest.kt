package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广告规则导入文件的校验用例。
 *
 * 重点锁住两条：未知来源 / 明文订阅一律不收；文件里缺的字段不许覆盖用户现有配置。
 */
class AdRulesCodecTest {

    private fun raw(
        text: String = "{}",
        schema: Int = AdRulesCodec.SCHEMA,
        app: String = AdRulesCodec.APP,
        kind: String = AdRulesCodec.KIND,
        rules: String = "",
        hasRules: Boolean = true,
        subs: List<String> = emptyList(),
        hasSubs: Boolean = true,
    ) = AdRulesCodec.Raw(text, schema, app, kind, rules, hasRules, subs, hasSubs)

    @Test
    fun acceptsValidRulesAndSubscriptions() {
        val parsed = AdRulesCodec.parse(
            raw(rules = "{\"keywords\":[\"跳过\"]}", subs = listOf("https://a.com/gkd.json5")),
        )
        assertEquals("{\"keywords\":[\"跳过\"]}", parsed.rules)
        assertEquals(setOf("https://a.com/gkd.json5"), parsed.subs)
        assertTrue(parsed.rulesPresent)
    }

    @Test
    fun rejectsForeignOrOutdatedFiles() {
        assertThrows(IllegalArgumentException::class.java) {
            AdRulesCodec.parse(raw(kind = "app-rules"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdRulesCodec.parse(raw(app = "com.other.app"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdRulesCodec.parse(raw(schema = 99))
        }
    }

    @Test
    fun rejectsOversizedTextAndEmptyFile() {
        assertThrows(IllegalArgumentException::class.java) {
            AdRulesCodec.parse(raw(text = "x".repeat(AdRulesCodec.MAX_TEXT + 1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AdRulesCodec.parse(raw(hasRules = false, hasSubs = false))
        }
    }

    @Test
    fun missingSubscriptionFieldKeepsExistingSubscriptions() {
        val parsed = AdRulesCodec.parse(raw(rules = "skip", hasSubs = false))
        assertTrue(parsed.rulesPresent)
        assertEquals("skip", parsed.rules)
        assertNull("没有 skipSubs 字段时不能覆盖现有订阅地址", parsed.subs)
    }

    @Test
    fun missingRulesFieldKeepsExistingRules() {
        val parsed = AdRulesCodec.parse(
            raw(hasRules = false, subs = listOf("https://b.com/gkd.json5")),
        )
        assertFalse(parsed.rulesPresent)
        assertEquals("", parsed.rules)
        assertEquals(setOf("https://b.com/gkd.json5"), parsed.subs)
    }

    @Test
    fun oversizedRulesAreTruncated() {
        val parsed = AdRulesCodec.parse(raw(rules = "x".repeat(AdRulesCodec.MAX_TEXT + 100)))
        assertEquals(AdRulesCodec.MAX_TEXT, parsed.rules.length)
    }

    @Test
    fun cleanSubsOnlyAcceptsHttpsAndDeduplicates() {
        val cleaned = AdRulesCodec.cleanSubs(
            listOf(
                " https://a.com/gkd.json5 ",
                "http://b.com/gkd.json5",
                "javascript:alert(1)",
                "https://a.com/gkd.json5", // 去重
                "",
                "https://c.com/gkd.json5",
            ),
        )
        assertEquals(
            setOf("https://a.com/gkd.json5", "https://c.com/gkd.json5"),
            cleaned,
        )
    }

    @Test
    fun cleanSubsRejectsLongUrlsAndCapsCount() {
        assertTrue(AdRulesCodec.cleanSubs(listOf("https://" + "a".repeat(600))).isEmpty())
        val many = (1..300).map { "https://sub$it.example.com/gkd.json5" }
        assertEquals(AdRulesCodec.MAX_SUBS, AdRulesCodec.cleanSubs(many).size)
    }

    @Test
    fun presentButEmptySubscriptionListStillReplaces() {
        // 字段存在但一条都不合法 = 用户明确导出了「无订阅」，替换成空集合是对的。
        val parsed = AdRulesCodec.parse(raw(subs = listOf("http://bad.example")))
        assertEquals(emptySet<String>(), parsed.subs)
    }
}
