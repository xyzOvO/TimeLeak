package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GKD 选择器里的取反条件必须真的生效。
 *
 * `!*=` 之前不被 PRED 正则识别，整条排除条件被静默丢掉；`!=` 则被
 * 当成 CONTAINS，把「名字里带这个词」的正常控件也一起排除了。
 * 前者是误点风险，后者是漏规则 —— 两者都在这组用例里锁住。
 */
class SkipEngineNegationTest {

    private fun load(matches: String) {
        val gkd = """
        {"apps":[{"id":"com.example.app","groups":[
          {"rules":[{"matches":"[${matches.replace("\"", "\\\"")}]"}]}
        ]}]}
        """.trimIndent()
        SkipEngine.ensureLoaded(gkd, "")
    }

    private fun loadWithExclude(matches: String, excludeMatches: String) {
        val esc = { s: String -> s.replace("\"", "\\\"") }
        val gkd = """
        {"apps":[{"id":"com.example.app","groups":[
          {"rules":[{"matches":"[${esc(matches)}]","excludeMatches":"[${esc(excludeMatches)}]"}]}
        ]}]}
        """.trimIndent()
        SkipEngine.ensureLoaded(gkd, "")
    }

    private fun hit(id: String, text: String = ""): SkipEngine.Action? =
        SkipEngine.scopedHit("com.example.app", null, text, "", "com.example.app:id/$id", 0)

    @Test
    fun negatedContainsBlocksLookalikeControl() {
        load("vid*=\"skip\" && vid!*=\"skip_login\"")
        assertEquals(
            "真正的开屏跳过按钮仍要命中",
            SkipEngine.Action.CLICK,
            hit("splash_skip_btn"),
        )
        assertNull(
            "登录页的 skip_login_btn 必须被 !*= 排除，不能当成跳过按钮点掉",
            hit("skip_login_btn"),
        )
    }

    @Test
    fun notEqualOnlyExcludesExactValue() {
        load("vid*=\"skip\" && vid!=\"skip\"")
        assertEquals(
            "!= 是精确不等于，不该把 splash_skip_btn 一起排除",
            SkipEngine.Action.CLICK,
            hit("splash_skip_btn"),
        )
        assertNull("恰好叫 skip 的控件被排除", hit("skip"))
    }

    @Test
    fun excludeMatchesBlocksLookalikeControl() {
        // excludeMatches 是「命中就不点」的正向选择器；改 gkdSpecs 签名后
        // 一度把它的返回值整个丢掉，导致登录页的相似按钮被误点。
        loadWithExclude("vid*=\"skip\"", "text*=\"跳过登录\"")
        assertEquals(
            "真正的跳过按钮不受影响",
            SkipEngine.Action.CLICK,
            hit("splash_skip_btn", "跳过广告"),
        )
        assertNull(
            "excludeMatches 命中的相似控件必须被排除",
            hit("skip_login_btn", "跳过登录"),
        )
    }
}
