package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    @Test
    fun parseSupportsCommonSubscriptionFormats() {
        val text = """
            # 注释
            ||Ads.Example.com^
            0.0.0.0 tracker.example.net
            DOMAIN-SUFFIX,metrics.example.org,REJECT
            - 'plain.example.io'
            @@||good.example.com^
            ! 注释
        """.trimIndent()
        val p = RuleParser.parseBoth(text)
        assertTrue(p.blocked.contains("ads.example.com"))
        assertTrue(p.blocked.contains("tracker.example.net"))
        assertTrue(p.blocked.contains("metrics.example.org"))
        assertTrue(p.blocked.contains("plain.example.io"))
        assertTrue(p.allowed.contains("good.example.com"))
    }

    @Test
    fun engineWalksSubdomainsAndAllowlistWins() {
        val e = RuleEngine()
        e.replace(listOf("example.com"), emptyList(), emptyList(), listOf("ok.example.com"))
        assertNotNull(e.decide("ads.example.com"))
        assertNull(e.decide("sub.ok.example.com"))
        assertNull(e.decide("other.com"))
        assertTrue(e.isAllowed("deep.ok.example.com"))
        assertFalse(e.isAllowed("ads.example.com"))
    }

    @Test
    fun publicSuffixIsNotTreatedAsRule() {
        val e = RuleEngine()
        e.replace(listOf("com"), emptyList(), emptyList(), emptyList())
        assertNull(e.decide("google.com"))
    }

    @Test
    fun normalizeRejectsNonDomains() {
        assertNull(normalizeDomain("1.2.3.4"))
        assertNull(normalizeDomain("localhost"))
        assertNull(normalizeDomain("a b.com"))
        assertNull(normalizeDomain("http://x.com"))
        assertEquals("ads.example.com", normalizeDomain("||Ads.Example.com^"))
    }

    @Test
    fun sourceIsReportedCorrectly() {
        val e = RuleEngine()
        e.replace(listOf("builtin.example"), listOf("custom.example"), listOf("sub.example"), emptyList())
        assertEquals(RuleSource.BUILTIN, e.decide("a.builtin.example")?.source)
        assertEquals(RuleSource.CUSTOM, e.decide("custom.example")?.source)
        assertEquals(RuleSource.SUBSCRIPTION, e.decide("x.sub.example")?.source)
    }
}
