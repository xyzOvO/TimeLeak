package com.chengliu.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

class CaptureRoutesTest {

    @Test
    fun normalizeAcceptsPublicAndRejectsPrivateOrWide() {
        assertEquals("8.8.8.8/32", CaptureRoutes.normalize("8.8.8.8"))
        assertEquals("8.8.8.0/24", CaptureRoutes.normalize("8.8.8.0/24"))
        assertNull(CaptureRoutes.normalize("10.0.0.1"))
        assertNull(CaptureRoutes.normalize("192.168.1.1"))
        assertNull(CaptureRoutes.normalize("127.0.0.1"))
        assertNull("不允许 /16 这类过宽网段", CaptureRoutes.normalize("8.8.8.8/16"))
        assertEquals("2606:4700::1111/128", CaptureRoutes.normalize("2606:4700::1111"))
        assertNull("ULA 不应被接管", CaptureRoutes.normalize("fd00::1"))
    }

    @Test
    fun coversV4HonoursPrefix() {
        val r = CaptureRoutes.build(listOf("8.8.8.0/24"))
        assertTrue(r.covers(bytes(8, 8, 8, 8)))
        assertTrue(r.covers(bytes(8, 8, 8, 254)))
        assertFalse(r.covers(bytes(8, 8, 9, 8)))
    }

    @Test
    fun excludeDropsTheWholeCoveringCidr() {
        val r = CaptureRoutes.build(
            listOf("1.1.1.1/32", "8.8.8.8/32"),
            listOf("1.1.1.1"),
        )
        assertEquals(listOf("8.8.8.8/32"), r.entries)
    }

    @Test
    fun literalRejectsHostnames() {
        assertNull(CaptureRoutes.literal("example.com"))
        assertNull(CaptureRoutes.literal("256.1.1.1"))
        assertArrayEquals(bytes(1, 1, 1, 1), CaptureRoutes.literal("1.1.1.1"))
    }

    @Test
    fun emptyPlanCoversNothing() {
        assertFalse(CaptureRoutes.EMPTY.covers(bytes(8, 8, 8, 8)))
        assertEquals(0, CaptureRoutes.EMPTY.size)
    }
}
