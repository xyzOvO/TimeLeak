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
    fun windowEventAloneIsNotEnoughWhenControlStillThere() {
        // 控件还看得见 = 那一下白点了。悬浮通知、气泡、输入法弹窗都会发窗口事件，
        // 把「有窗口事件」当成功会让统计虚高（真机踩过）。
        assertFalse(EffectVerdict.judge(true, false, false, true))
    }

    @Test
    fun windowEventCountsWhenVisibilityUnknown() {
        // 查不到控件（没有 id / 拿不到根）时，只能拿窗口事件当弱证据
        assertTrue(EffectVerdict.judge(null, false, false, true))
    }

    @Test
    fun vanishedControlCountsEvenWithoutWindowEvent() {
        assertTrue(EffectVerdict.judge(false, false, false, true))
    }
}
