package com.chengliu.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectVerdictTest {

    @Test
    fun nothingChangedMeansClickWasWasted() {
        assertFalse(EffectVerdict.judge(true, false, false, false))
    }

    @Test
    fun vanishedControlCountsAsSkipped() {
        assertTrue(EffectVerdict.judge(false, false, false, false))
    }

    @Test
    fun unknownVisibilityNeverClaimsSuccess() {
        // 没有 viewId、拿不到根节点时不能瞎报成功
        assertFalse(EffectVerdict.judge(null, false, false, false))
    }

    @Test
    fun activityChangeCountsAsSkipped() {
        assertTrue(EffectVerdict.judge(true, true, false, false))
    }

    @Test
    fun packageChangeCountsAsSkipped() {
        assertTrue(EffectVerdict.judge(true, false, true, false))
    }

    @Test
    fun newWindowCountsAsSkipped() {
        assertTrue(EffectVerdict.judge(true, false, false, true))
    }

    @Test
    fun windowChangeWinsOverStillVisible() {
        // 控件还在但已经换了窗口（第二层广告 / 新页面），仍算这次点击生效
        assertTrue(EffectVerdict.judge(true, false, false, true))
    }
}
