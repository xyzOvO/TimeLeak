package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 几何猜测候选的评分：那个「无文字、可点、贴在角落、个头很小」的控件，
 * 必须靠自己把所有闸门走完拿到高置信度，而不是绕过判定。
 */
class GeometryGuessScoreTest {

    private fun facts(
        geometry: Boolean = true,
        mode: PolicyMode = PolicyMode.FULL,
        inSplash: Boolean = true,
    ) = NodeFacts(
        pkg = "com.example.forumapp",
        text = "",
        desc = "",
        id = "",
        cls = "android.view.View",
        left = 44, top = 273, right = 154, bottom = 383,
        screenW = 1080, screenH = 2400,
        clickable = true,
        visible = true,
        inSplash = inSplash,
        reason = if (geometry) HitReason.GEOMETRY else HitReason.TEXT_GUESS,
        kind = AdKind.SPLASH,
        geometry = geometry,
        mode = mode,
    )

    @Test
    fun geometryGuessInSplashIsHighConfidence() {
        val d = DecisionCore.decide(facts())
        assertEquals(Confidence.HIGH, d.level)
        assertEquals(DecisionAction.CLICK, d.action)
        assertTrue(d.score >= Weights.HIGH_MIN)
    }

    @Test
    fun sameFactsWithoutTheFlagStayLow() {
        val d = DecisionCore.decide(facts(geometry = false))
        assertTrue(d.level != Confidence.HIGH)
        assertTrue(d.action != DecisionAction.CLICK)
    }

    @Test
    fun strictPolicyRejectsGeometryGuess() {
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
    fun ruleNameSaysGeom() {
        val d = DecisionCore.decide(facts())
        assertTrue(d.rule, d.rule.startsWith("geom:"))
    }
}
