package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆命中：上次点中并复核生效的同一个控件，这一轮要能靠「记住的 id」直接过关，
 * 而不是靠文案匹配——有的应用把真跳过按钮做成一个没有文字、只有 id 的图标。
 */
class MemoryHintTest {

    private fun facts(
        remembered: Boolean = true,
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
        reason = if (remembered) HitReason.MEMORY else HitReason.TEXT_GUESS,
        kind = AdKind.SPLASH,
        remembered = remembered,
        mode = mode,
    )

    @Test
    fun rememberedTargetInSplashIsHighConfidence() {
        val d = DecisionCore.decide(facts())
        assertEquals(Confidence.HIGH, d.level)
        assertEquals(DecisionAction.CLICK, d.action)
        assertTrue(d.score >= Weights.HIGH_MIN)
    }

    @Test
    fun withoutTheFlagAnIdOnlyNodeStaysLow() {
        val d = DecisionCore.decide(facts(remembered = false))
        assertTrue(d.level != Confidence.HIGH)
        assertTrue(d.action != DecisionAction.CLICK)
    }

    @Test
    fun textGuessOffDoesNotBlockMemory() {
        val d = DecisionCore.decide(facts(mode = PolicyMode.STRICT_SPLASH))
        assertTrue(d.policyAllowed)
        assertEquals(DecisionAction.CLICK, d.action)
    }

    @Test
    fun strictPolicyStillRejectsSiblingOnly() {
        val sibling = facts(mode = PolicyMode.STRICT_SPLASH).copy(
            reason = HitReason.SIBLING_ICON,
            remembered = false,
            siblingIcon = true,
        )
        assertFalse(DecisionCore.decide(sibling).policyAllowed)
    }

    @Test
    fun ruleReadsAsMemory() {
        val d = DecisionCore.decide(facts())
        assertTrue(d.rule.startsWith("memory:"))
    }

    @Test
    fun outsideSplashWindowMemoryNoLongerClicks() {
        val d = DecisionCore.decide(facts(inSplash = false))
        assertTrue(d.level != Confidence.HIGH)
    }
}
