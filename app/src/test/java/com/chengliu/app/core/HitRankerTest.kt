package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Test

class HitRankerTest {

    private fun key(
        clickable: Boolean,
        w: Int,
        h: Int,
        left: Int,
        top: Int,
        screenW: Int = 1080,
        screenH: Int = 2400,
    ) = HitRanker.RankKey(
        clickable = clickable,
        width = w,
        height = h,
        left = left,
        top = top,
        right = left + w,
        bottom = top + h,
        screenW = screenW,
        screenH = screenH,
    )

    @Test
    fun clickableCandidateComesFirst() {
        val passive = key(false, 180, 70, 900, 1900)
        val clickable = key(true, 180, 70, 900, 1900)
        val ordered = HitRanker.order(listOf(passive, clickable)) { it }
        assertEquals(true, ordered.first().clickable)
    }

    @Test
    fun smallerCandidateComesFirst() {
        val big = key(true, 400, 160, 600, 2100)
        val small = key(true, 160, 60, 900, 2200)
        val ordered = HitRanker.order(listOf(big, small)) { it }
        assertEquals(160, ordered.first().width)
    }

    @Test
    fun closerToCornerComesFirst() {
        val center = key(true, 180, 70, 400, 1100)
        val corner = key(true, 180, 70, 880, 2280)
        val ordered = HitRanker.order(listOf(center, corner)) { it }
        assertEquals(2280, ordered.first().top)
    }

    @Test
    fun equalKeysKeepOriginalOrder() {
        val a = key(true, 180, 70, 880, 2280)
        val b = key(true, 180, 70, 880, 2280)
        val ordered = HitRanker.order(listOf(a, b)) { it }
        assertEquals(a, ordered[0])
        assertEquals(b, ordered[1])
    }

    @Test
    fun singleItemIsReturnedAsIs() {
        val only = key(true, 180, 70, 0, 0)
        assertEquals(listOf(only), HitRanker.order(listOf(only)) { it })
    }
}
