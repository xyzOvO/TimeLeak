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
    fun reanchorAcceptsSlightMove() {
        // 同一个按钮飘了 30px、尺寸没变：可以按新坐标点
        assertTrue(
            HitGeometry.canReanchor(
                63, 47, 911, 273,
                63, 47, 941, 273,
                1080, 2400,
            ),
        )
    }

    @Test
    fun reanchorRejectsResizeOrRunaway() {
        // 尺寸明显变了（68 → 200 宽）：不是同一个东西
        assertFalse(HitGeometry.canReanchor(63, 47, 911, 273, 200, 47, 911, 273, 1080, 2400))
        // 跑到屏幕另一头（x 差 500px > 40% 屏宽）：换页了
        assertFalse(HitGeometry.canReanchor(63, 47, 300, 273, 63, 47, 800, 273, 1080, 2400))
        // 退化输入一律不放行
        assertFalse(HitGeometry.canReanchor(0, 0, 911, 273, 63, 47, 911, 273, 1080, 2400))
    }

    @Test
    fun containsAcceptsInnerBoxOnly() {
        assertTrue(HitGeometry.contains(0, 0, 100, 100, 10, 10, 90, 90))
        assertTrue(HitGeometry.contains(0, 0, 100, 100, 0, 0, 100, 100))
        assertFalse(HitGeometry.contains(0, 0, 100, 100, 10, 10, 101, 90))
        assertFalse(HitGeometry.contains(0, 0, 100, 100, -1, 10, 90, 90))
    }

    @Test
    fun smallControlAcceptsCornerSkipButton() {
        // 真机样本：自绘的圆形「跳过」按钮 110×110，屏 1080×2400
        assertTrue(HitGeometry.isSmallControl(110, 110, 1080, 2400))
    }

    @Test
    fun smallControlRejectsCardsAndFullRows() {
        // 整行横条、大卡片、退化输入都不算「小控件」
        assertFalse(HitGeometry.isSmallControl(800, 200, 1080, 2400))
        assertFalse(HitGeometry.isSmallControl(600, 400, 1080, 2400))
        assertFalse(HitGeometry.isSmallControl(0, 0, 1080, 2400))
    }

    @Test
    fun topCornerBandAcceptsTopCornersOnly() {
        // 真机样本：左上角自绘跳过 99,328；右上角跳过 911,273
        assertTrue(HitGeometry.isTopCornerBand(99, 328, 1080, 2400))
        assertTrue(HitGeometry.isTopCornerBand(911, 273, 1080, 2400))
        // 真机误判样本：左下角 88,2125 / 29,1916，都是普通 UI（悬浮球 / 导航图标），必须排除
        assertFalse(HitGeometry.isTopCornerBand(88, 2125, 1080, 2400))
        assertFalse(HitGeometry.isTopCornerBand(29, 1916, 1080, 2400))
        // 屏幕中央、只贴一条边、退化输入都不算角落
        assertFalse(HitGeometry.isTopCornerBand(540, 1200, 1080, 2400))
        assertFalse(HitGeometry.isTopCornerBand(540, 200, 1080, 2400))
        assertFalse(HitGeometry.isTopCornerBand(99, 328, 0, 0))
    }

    @Test
    fun coversScreenRequiresNearlyFullscreen() {
        assertTrue(HitGeometry.coversScreen(1080, 2400, 1080, 2400))
        // 底下留一条状态栏 / 导航栏的空：仍算整屏
        assertTrue(HitGeometry.coversScreen(1080, 2000, 1080, 2400))
        // 悬浮通知那种小窗：一角而已，不算整屏
        assertFalse(HitGeometry.coversScreen(600, 400, 1080, 2400))
        assertFalse(HitGeometry.coversScreen(0, 0, 1080, 2400))
    }
}
