package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitGeometryTest {

    @Test
    fun areaRatioCountsWholeScreen() {
        assertEquals(1.0f, HitGeometry.areaRatio(1080, 2400, 1080, 2400), 0.001f)
    }

    @Test
    fun areaRatioIsZeroForDegenerateInput() {
        assertEquals(0.0f, HitGeometry.areaRatio(0, 100, 1080, 2400), 0.001f)
        assertEquals(0.0f, HitGeometry.areaRatio(100, 100, 0, 0), 0.001f)
    }

    @Test
    fun selfClickableIsNeverATrapEvenIfHuge() {
        assertFalse(HitGeometry.isTrapAncestor(true, 1080, 2400, 1080, 2400))
    }

    @Test
    fun fullScreenAncestorWithPassiveChildIsATrap() {
        // 假按钮：自己不可点，可点祖先是整屏广告图
        assertTrue(HitGeometry.isTrapAncestor(false, 1080, 2200, 1080, 2400))
    }

    @Test
    fun smallAncestorIsNotATrap() {
        // 真按钮包在小容器里：可点祖先只占几个百分点
        assertFalse(HitGeometry.isTrapAncestor(false, 180, 70, 1080, 2400))
    }

    @Test
    fun thresholdSitsAtHalfScreen() {
        // 正好一半不算，超过才算
        assertFalse(HitGeometry.isTrapAncestor(false, 540, 2400, 1080, 2400))
        assertTrue(HitGeometry.isTrapAncestor(false, 560, 2400, 1080, 2400))
    }

    @Test
    fun containsAcceptsInnerBoxOnly() {
        assertTrue(HitGeometry.contains(0, 0, 100, 100, 10, 10, 90, 90))
        assertTrue(HitGeometry.contains(0, 0, 100, 100, 0, 0, 100, 100))
        assertFalse(HitGeometry.contains(0, 0, 100, 100, 10, 10, 101, 90))
        assertFalse(HitGeometry.contains(0, 0, 100, 100, -1, 10, 90, 90))
    }
}
