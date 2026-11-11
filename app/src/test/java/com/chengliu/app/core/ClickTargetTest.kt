package com.chengliu.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击重试点必须认得出「同一个倒计时按钮」。
 *
 * 精确坐标做 key 会让「跳过 10s」→「跳过 9s」的几像素位移判成新控件，
 * 重试机制形同虚设；这里锁住与 verifyBeforeClick 一致的 16px 容差边界。
 */
class ClickTargetTest {

    private val base = "com.bili:id/skip|跳过|"

    @Test fun exactSameIsSameTarget() {
        assertTrue(ClickTarget.same(base, 900, 120, base, 900, 120))
    }

    @Test fun countdownWidthChangeIsSameTarget() {
        // 按钮右对齐、位数从两位变一位，左边会挪几像素
        assertTrue(ClickTarget.same(base, 888, 120, base, 900, 120))
    }

    @Test fun driftAtBoundaryIsSameTarget() {
        assertTrue(ClickTarget.same(base, 900, 136, base, 900, 120))
        assertTrue(ClickTarget.same(base, 884, 120, base, 900, 120))
    }

    @Test fun beyondBoundaryIsDifferentTarget() {
        assertFalse(ClickTarget.same(base, 917, 120, base, 900, 120))
        assertFalse(ClickTarget.same(base, 900, 103, base, 900, 120))
    }

    @Test fun differentTextIsDifferentTargetEvenAtSamePlace() {
        assertFalse(ClickTarget.same("other|关闭|", 900, 120, base, 900, 120))
    }

    @Test fun noPreviousClickIsNeverSameTarget() {
        assertFalse(ClickTarget.same(base, 900, 120, "", 900, 120))
    }
}
