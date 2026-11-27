package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HitMemoryTest {

    @Test
    fun remembersPerPackage() {
        HitMemory.clear()
        HitMemory.remember("com.a", "com.a:id/skip")
        HitMemory.remember("com.b", "com.b:id/count_down")
        assertEquals("com.a:id/skip", HitMemory.viewIdFor("com.a"))
        assertEquals("com.b:id/count_down", HitMemory.viewIdFor("com.b"))
    }

    @Test
    fun laterHitOverwritesEarlier() {
        HitMemory.clear()
        HitMemory.remember("com.a", "com.a:id/skip")
        HitMemory.remember("com.a", "com.a:id/skip_ad")
        assertEquals("com.a:id/skip_ad", HitMemory.viewIdFor("com.a"))
    }

    @Test
    fun forgetDropsEntry() {
        HitMemory.clear()
        HitMemory.remember("com.a", "com.a:id/skip")
        HitMemory.forget("com.a")
        assertNull(HitMemory.viewIdFor("com.a"))
    }

    @Test
    fun ignoresBlankAndNull() {
        HitMemory.clear()
        HitMemory.remember(null, "com.a:id/skip")
        HitMemory.remember("com.a", null)
        HitMemory.remember("", "com.a:id/skip")
        HitMemory.remember("com.a", "")
        assertEquals(0, HitMemory.size())
        assertNull(HitMemory.viewIdFor("com.a"))
    }

    @Test
    fun ignoresStringsThatAreNotViewIds() {
        HitMemory.clear()
        // 无障碍树里偶尔会给出没有 ":id/" 的串（纯类名、空壳），记了也用不上。
        HitMemory.remember("com.a", "android.widget.TextView")
        assertEquals(0, HitMemory.size())
    }

    @Test
    fun boundedAndKeepsNewest() {
        HitMemory.clear()
        for (i in 0 until 100) HitMemory.remember("pkg$i", "pkg$i:id/skip")
        assertEquals(64, HitMemory.size())
        // 最近的（后写入的）必须还在，最老的必须被淘汰。
        assertEquals("pkg99:id/skip", HitMemory.viewIdFor("pkg99"))
        assertNull(HitMemory.viewIdFor("pkg0"))
    }
}
