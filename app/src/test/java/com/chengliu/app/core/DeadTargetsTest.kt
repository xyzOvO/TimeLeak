package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DeadTargetsTest {

    private val pkg = "com.example.forumapp"
    private val id = "com.example.forumapp:id/ad_label_1"

    @Before
    fun setUp() {
        DeadTargets.clear()
    }

    @Test
    fun rememberedTargetIsDead() {
        DeadTargets.remember(pkg, id, 1_000L)
        assertTrue(DeadTargets.isDead(pkg, id, 1_100L))
    }

    @Test
    fun unknownTargetIsAlive() {
        assertFalse(DeadTargets.isDead(pkg, id, 1_100L))
    }

    @Test
    fun expiresAfterTtl() {
        DeadTargets.remember(pkg, id, 1_000L)
        assertTrue(DeadTargets.isDead(pkg, id, 1_000L + DeadTargets.TTL_MS - 1))
        assertFalse(DeadTargets.isDead(pkg, id, 1_000L + DeadTargets.TTL_MS + 1))
    }

    @Test
    fun ignoresStringsThatAreNotViewIds() {
        DeadTargets.remember(pkg, "跳过广告1", 1_000L)
        assertFalse(DeadTargets.isDead(pkg, "跳过广告1", 1_100L))
    }

    @Test
    fun ignoresBlankPackage() {
        DeadTargets.remember("", id, 1_000L)
        assertFalse(DeadTargets.isDead("", id, 1_100L))
    }

    @Test
    fun clearForgetsEverything() {
        DeadTargets.remember(pkg, id, 1_000L)
        DeadTargets.clear()
        assertFalse(DeadTargets.isDead(pkg, id, 1_100L))
    }

    @Test
    fun staysBoundedWhenFlooded() {
        for (i in 0 until DeadTargets.MAX * 2) {
            DeadTargets.remember(pkg, "$pkg:id/view_$i", 1_000L)
        }
        assertEquals(DeadTargets.MAX, DeadTargets.size())
        // 最新的还在
        assertTrue(DeadTargets.isDead(pkg, "$pkg:id/view_${DeadTargets.MAX * 2 - 1}", 1_100L))
    }
}
