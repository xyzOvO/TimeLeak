package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计数上屏的节流闸门。
 *
 * 关心两件事：窗口内不许重复发射（省电的来源），窗口过去之后必须能发（不然数字就卡住了）。
 */
class EmitThrottleTest {

    @Test
    fun firstCallAlwaysPasses() {
        val g = EmitThrottle(250L)
        assertTrue(g.tryAcquire(0L))
    }

    @Test
    fun secondCallInsideWindowIsRejected() {
        val g = EmitThrottle(250L)
        assertTrue(g.tryAcquire(1000L))
        assertFalse(g.tryAcquire(1100L))
        assertFalse(g.tryAcquire(1249L))
        assertTrue("到窗口边界就该放行", g.tryAcquire(1250L))
    }

    @Test
    fun waitMsPointsAtWindowEnd() {
        val g = EmitThrottle(250L)
        g.tryAcquire(1000L)
        assertEquals(150L, g.waitMs(1100L))
        assertEquals(1L, g.waitMs(1500L))
    }

    @Test
    fun markEmittedRestartsWindow() {
        val g = EmitThrottle(250L)
        assertTrue(g.tryAcquire(1000L))
        g.markEmitted(1200L)
        assertFalse(g.tryAcquire(1300L))
        assertTrue(g.tryAcquire(1450L))
    }
}
