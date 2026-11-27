package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补位候选的评分：那个「无文字、窄、可点」的跳过图标，
 * 必须靠自己拿到高置信度，而不是靠绕过判定。
 */
class SiblingIconScoreTest {

    private fun facts(
        sibling: Boolean = true,
        mode: PolicyMode = PolicyMode.FULL,
        inSplash: Boolean = true,
    ) = NodeFacts(
        pkg = "com.example.forumapp",
        text = "",
        desc = "",
        id = "com.example.forumapp:id/ad_icon",
        cls = "android.widget.ImageView",
        left = 846, top = 2165, right = 1025, bottom = 2234,
        screenW = 1080, screenH = 2400,
        clickable = true,
        visible = true,
        inSplash = inSplash,
        reason = if (sibling) HitReason.SIBLING_ICON else HitReason.TEXT_GUESS,
        kind = AdKind.SPLASH,
        siblingIcon = sibling,
        mode = mode,
    )

    @Test
    fun siblingIconInSplashIsHighConfidence() {
        val d = DecisionCore.decide(facts())
        assertEquals(Confidence.HIGH, d.level)
        assertEquals(DecisionAction.CLICK, d.action)
        assertTrue(d.score >= Weights.HIGH_MIN)
    }

    @Test
    fun sameFactsWithoutTheFlagStayLow() {
        val d = DecisionCore.decide(facts(sibling = false))
        assertTrue(d.level != Confidence.HIGH)
        assertTrue(d.action != DecisionAction.CLICK)
    }

    @Test
    fun strictPolicyRejectsSiblingInference() {
        val d = DecisionCore.decide(facts(mode = PolicyMode.STRICT_SPLASH))
        assertFalse(d.policyAllowed)
        assertEquals(DecisionAction.SKIP, d.action)
    }

    @Test
    fun outsideSplashWindowItNoLongerClicks() {
        val d = DecisionCore.decide(facts(inSplash = false))
        assertTrue(d.level != Confidence.HIGH)
        assertTrue(d.action != DecisionAction.CLICK)
    }

    @Test
    fun ruleNameSaysSibling() {
        val d = DecisionCore.decide(facts())
        assertTrue(d.rule, d.rule.startsWith("sibling:"))
    }
}
