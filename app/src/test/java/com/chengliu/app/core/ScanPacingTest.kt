package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避档位必须可预测 —— 这是「省电」这条线上唯一能拿数字说事的地方。
 *
 * 判据改过一次：v1.4.0 拿「候选数」当信号，可开屏广告页每一轮都能数出几个窄小
 * 候选（倒计时、跳过、关闭），于是永远不复位，退避在最需要它的页面上从不生效。
 * 现在只认「这一轮有没有真的点下去」。
 */
class ScanPacingTest {

    @Test
    fun startsAtBaseDelay() {
        val p = ScanPacer()
        assertEquals(120L, p.mergeDelayMs)
        assertEquals(0, p.idleStreak)
    }

    @Test
    fun backsOffWhenNothingClicked() {
        val p = ScanPacer()
        assertEquals(240L, p.onScan(false, false))
        assertEquals(360L, p.onScan(false, false))
        assertEquals(480L, p.onScan(false, false))
        // 到底之后就停住，不会一直涨
        assertEquals(480L, p.onScan(false, false))
        assertEquals(480L, p.onScan(false, false))
    }

    @Test
    fun hitRestoresResponsiveness() {
        val p = ScanPacer()
        p.onScan(false, false)
        p.onScan(false, false)
        assertEquals(360L, p.mergeDelayMs)
        assertEquals(120L, p.onScan(true, false))
        assertEquals(0, p.idleStreak)
    }

    @Test
    fun splashWindowNeverBacksOff() {
        // 开屏窗要速度：不拿广告速度换省电
        val p = ScanPacer()
        repeat(6) { assertEquals(120L, p.onScan(false, true)) }
        assertEquals(0, p.idleStreak)
    }

    @Test
    fun resetRestoresResponsiveness() {
        val p = ScanPacer()
        repeat(6) { p.onScan(false, false) }
        // 换窗口 / 用户自己动手之后必须立刻恢复灵敏
        p.reset()
        assertEquals(120L, p.mergeDelayMs)
    }

    @Test
    fun boundsAreConfigurable() {
        val p = ScanPacer(baseMs = 50L, stepMs = 25L, maxMs = 90L)
        assertEquals(75L, p.onScan(false, false))
        assertEquals(90L, p.onScan(false, false))
        assertTrue("上限必须真的截断", p.mergeDelayMs <= 90L)
    }
}
