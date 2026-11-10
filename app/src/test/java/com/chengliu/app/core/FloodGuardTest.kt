package com.chengliu.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FloodGuardTest {

    @Before
    fun reset() {
        FloodGuard.reset()
    }

    @Test
    fun allowsUpToLimitThenThrottles() {
        val name = "storm.example.com"
        repeat(80) { i ->
            assertTrue("第 ${i + 1} 次查询应放行", FloodGuard.admit(name))
        }
        assertFalse("第 81 次应触发熔断", FloodGuard.admit(name))
    }

    @Test
    fun differentNamesAreIndependent() {
        repeat(80) { FloodGuard.admit("a.example.com") }
        assertFalse(FloodGuard.admit("a.example.com"))
        assertTrue("其它域名不应被牵连", FloodGuard.admit("b.example.com"))
    }

    @Test
    fun resetClearsState() {
        repeat(80) { FloodGuard.admit("c.example.com") }
        FloodGuard.reset()
        assertTrue(FloodGuard.admit("c.example.com"))
    }
}
