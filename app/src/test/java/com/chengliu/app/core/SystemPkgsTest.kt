package com.chengliu.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系统包过滤是省电这条线上唯一「零风险、立刻见效」的一环：
 * 扫错一个系统包，换来的是一整屏内容变化事件的全树遍历。
 */
class SystemPkgsTest {

    @Test
    fun launcherIsSkipped() {
        assertTrue(SystemPkgs.isSkip("com.miui.home"))
        assertTrue(SystemPkgs.isSkip("com.android.launcher3"))
    }

    @Test
    fun systemSearchIsSkipped() {
        // v1.4.0 真机实测里最贵的那一个：一轮 99 次扫描，它占 49 次。
        assertTrue(SystemPkgs.isSkip("com.android.quicksearchbox"))
        assertTrue(SystemPkgs.isSkip("com.google.android.googlequicksearchbox"))
    }

    @Test
    fun normalAppIsNotSkipped() {
        assertFalse(SystemPkgs.isSkip("com.tencent.mm"))
        assertFalse(SystemPkgs.isSkip("com.coolapk.market"))
        assertFalse(SystemPkgs.isSkip("tv.danmaku.bili"))
    }

    @Test
    fun blankCountsAsSystem() {
        assertTrue(SystemPkgs.isSkip(null))
        assertTrue(SystemPkgs.isSkip(""))
    }

    @Test
    fun registeredHomeIsSkipped() {
        SystemPkgs.registerHome(listOf("com.example.customhome"))
        assertTrue(SystemPkgs.isSkip("com.example.customhome"))
        // 登记桌面不能误伤普通应用
        assertFalse(SystemPkgs.isSkip("com.example.notahome"))
    }

    @Test
    fun junkIsIgnored() {
        SystemPkgs.registerHome(listOf("", "not a pkg", "nodot"))
        assertFalse(SystemPkgs.isSkip("nodot"))
        assertFalse(SystemPkgs.isSkip("not a pkg"))
    }
}
