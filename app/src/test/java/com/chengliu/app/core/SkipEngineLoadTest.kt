package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则装载层的行为锁：正则选择器要真的命中、超长正则该被丢掉、
 * 「有没有 class 选择器」这个判断要准（[SkipEngine.needsClass] 直接决定
 * 扫描时要不要为每个节点多读一次 className）。
 */
class SkipEngineLoadTest {

    private fun load(vararg matches: String) {
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val rules = matches.joinToString(",") { """{"matches":"${esc(it)}"}""" }
        val gkd =
            """{"apps":[{"id":"com.demo","groups":[{"matchTime":9000,"rules":[$rules]}]}]}"""
        SkipEngine.ensureLoaded(gkd, "")
    }

    @Test
    fun loadsGroupsAndRules() {
        load("""[text*="跳过"]""", """[vid*="skip"]""")
        assertEquals(1, SkipEngine.groupCount())
        assertEquals(2, SkipEngine.ruleCount())
    }

    @Test
    fun noClassSelectorMeansNoClassNameRead() {
        load("""[text*="跳过"]""", """[vid*="skip"]""")
        assertFalse("没有 [name=...] 就不该要求扫描时读 className", SkipEngine.needsClass)
    }

    @Test
    fun classSelectorIsDetected() {
        load("""[name*="Skip"]""")
        assertTrue("写了 class 选择器就必须如实上报", SkipEngine.needsClass)
    }

    @Test
    fun regexSelectorMatches() {
        load("""[text~="^跳.*广告$"]""")
        assertEquals(
            SkipEngine.Action.CLICK,
            SkipEngine.scopedHit("com.demo", null, "跳过广告", "", "", 1000L),
        )
    }

    @Test
    fun regexSelectorStaysCaseInsensitive() {
        load("""[text~="^skip.*"]""")
        assertEquals(
            SkipEngine.Action.CLICK,
            SkipEngine.scopedHit("com.demo", null, "Skip Ads", "", "", 1000L),
        )
    }

    @Test
    fun overlongRegexIsDropped() {
        // 超过 60 字符的正则一律丢弃：这种规则几乎必然是作者写错了，
        // 留着只会让每次匹配都跑一遍灾难性回溯。
        load("""[text~="${"a".repeat(80)}"]""")
        assertEquals(0, SkipEngine.ruleCount())
    }

    @Test
    fun wrongPackageDoesNotMatch() {
        load("""[text*="跳过"]""")
        assertEquals(null, SkipEngine.scopedHit("com.other", null, "跳过", "", "", 1000L))
        assertEquals(
            SkipEngine.Action.CLICK,
            SkipEngine.scopedHit("com.demo", null, "跳过", "", "", 1000L),
        )
    }
}
