package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuessTargetsTest {

    @Test
    fun listedPackageIsBold() {
        val apps = setOf("com.example.forumapp", "com.example.video")
        assertEquals(GuessTargets.Mode.BOLD, GuessTargets.modeFor("com.example.video", apps))
    }

    @Test
    fun unlistedPackageIsOff() {
        val apps = setOf("com.example.video")
        assertEquals(GuessTargets.Mode.OFF, GuessTargets.modeFor("com.example.settings", apps))
    }

    @Test
    fun emptyListOrNullPackageNeverGuesses() {
        assertEquals(GuessTargets.Mode.OFF, GuessTargets.modeFor("com.example.video", emptySet()))
        assertEquals(GuessTargets.Mode.OFF, GuessTargets.modeFor(null, setOf("com.example.video")))
    }

    @Test
    fun boldGetsTwoTriesAndOffGetsOne() {
        assertEquals(2, GuessTargets.maxTries(GuessTargets.Mode.BOLD))
        assertEquals(1, GuessTargets.maxTries(GuessTargets.Mode.OFF))
    }

    @Test
    fun normalizePkgAcceptsRealPackageNames() {
        assertEquals("com.example.app", GuessTargets.normalizePkg("  COM.Example.App "))
        assertEquals("io.github.demo_1.tool", GuessTargets.normalizePkg("io.github.demo_1.tool"))
    }

    @Test
    fun normalizePkgRejectsHalfTypedInput() {
        // 真机上用户输「com」就点「按包名放行」，以前会静默失败，看着像按钮坏了
        assertNull(GuessTargets.normalizePkg("com"))
        assertNull(GuessTargets.normalizePkg("com."))
        assertNull(GuessTargets.normalizePkg("com..app"))
        assertNull(GuessTargets.normalizePkg(""))
        assertNull(GuessTargets.normalizePkg("1com.example.app"))
        assertNull(GuessTargets.normalizePkg("com.example app"))
    }

    @Test
    fun boldDoesNotRequireAdEvidence() {
        // 名单是用户自己放行的，放开档不再靠广告话术当前置证据；
        // 保守档仍然要话术（否则任何普通页面都能触发猜测）。
        assertFalse(GuessTargets.needsAdEvidence(GuessTargets.Mode.BOLD))
        assertTrue(GuessTargets.needsAdEvidence(GuessTargets.Mode.OFF))
    }
}
