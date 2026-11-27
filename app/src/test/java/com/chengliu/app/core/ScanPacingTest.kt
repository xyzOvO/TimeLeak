package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 空转退避的档位必须可预测 —— 这是「省电」这条线上唯一能拿数字说事的地方：
 * 连续 4 轮扫不到候选之后，内容变化事件触发的扫描从每秒最多 8 次降到每秒 2 次。
 */
class ScanPacingTest {

    @Test
    fun startsAtBaseDelay() {
        val p = ScanPacer()
        assertEquals(120L, p.mergeDelayMs)
        assertEquals(0, p.idleStreak)
    }

    @Test
    fun backsOffStepByStepThenCaps() {
        val p = ScanPacer()
        assertEquals(240L, p.onScan(0))
        assertEquals(360L, p.onScan(0))
        assertEquals(480L, p.onScan(0))
        // 到底之后就停住，不会一直涨
        assertEquals(480L, p.onScan(0))
        assertEquals(480L, p.onScan(0))
    }

    @Test
    fun anyCandidateResets() {
        val p = ScanPacer()
        p.onScan(0)
        p.onScan(0)
        assertEquals(360L, p.mergeDelayMs)
        assertEquals(120L, p.onScan(3))
        assertEquals(0, p.idleStreak)
    }

    @Test
    fun resetRestoresResponsiveness() {
        val p = ScanPacer()
        repeat(6) { p.onScan(0) }
        // 换窗口 / 用户自己动手之后必须立刻恢复灵敏
        p.reset()
        assertEquals(120L, p.mergeDelayMs)
    }

    @Test
    fun boundsAreConfigurable() {
        val p = ScanPacer(baseMs = 50L, stepMs = 25L, maxMs = 90L)
        assertEquals(75L, p.onScan(0))
        assertEquals(90L, p.onScan(0))
        assertTrue("上限必须真的截断", p.mergeDelayMs <= 90L)
    }
}
