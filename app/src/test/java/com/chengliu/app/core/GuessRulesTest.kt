package com.chengliu.app.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuessRulesTest {

    // ---- AdHints ----

    @Test
    fun complianceLabelCounts() {
        assertTrue(AdHints.looksLikeAd("广告", ""))
        assertTrue(AdHints.looksLikeAd("", "推广"))
    }

    @Test
    fun hotspotWordingCounts() {
        assertTrue(AdHints.looksLikeAd("点击到落地页或三方APP", ""))
        assertTrue(AdHints.looksLikeAd("点击跳转至详情页或者第三方应用", ""))
    }

    @Test
    fun ordinaryCopyDoesNotCount() {
        assertFalse(AdHints.looksLikeAd("关注", "发现"))
        assertFalse(AdHints.looksLikeAd("", ""))
    }

    @Test
    fun hotspotOnlyAcceptsCallToAction() {
        assertTrue(AdHints.looksLikeHotspot("点击到落地页或三方APP", ""))
        assertTrue(AdHints.looksLikeHotspot("点击跳转至详情页或者第三方应用", ""))
        // 信息流里的「广告」标签不算整屏广告的证据
        assertFalse(AdHints.looksLikeHotspot("广告", "推广"))
        assertTrue(AdHints.looksLikeAd("广告", "推广"))
    }

    // ---- GeoGuess.shouldGuess ----

    @Test
    fun guessesOnlyWithHint() {
        assertTrue(GeoGuess.shouldGuess(true, false, false, 2, true))
        assertFalse(GeoGuess.shouldGuess(true, false, false, 2, false))
    }

    @Test
    fun neverGuessesTwiceInOneWindow() {
        assertFalse(GeoGuess.shouldGuess(true, true, false, 2, true))
    }

    @Test
    fun neverGuessesWhenSwitchIsOff() {
        assertFalse(GeoGuess.shouldGuess(false, false, false, 2, true))
    }

    @Test
    fun neverGuessesWithoutCandidatesOrAfterAClick() {
        assertFalse(GeoGuess.shouldGuess(true, false, false, 0, true))
        assertFalse(GeoGuess.shouldGuess(true, false, true, 2, true))
    }

    // ---- GeoGuess.needsDeepHint ----

    @Test
    fun deepHintOnlyWhenShallowFoundNothing() {
        assertTrue(GeoGuess.needsDeepHint(true, false, 2, false))
        assertFalse(GeoGuess.needsDeepHint(true, false, 2, true))
    }

    @Test
    fun deepHintSkippedWhenOffOrAlreadyGuessedOrNoCandidates() {
        assertFalse(GeoGuess.needsDeepHint(false, false, 2, false))
        assertFalse(GeoGuess.needsDeepHint(true, true, 2, false))
        assertFalse(GeoGuess.needsDeepHint(true, false, 0, false))
    }
}
