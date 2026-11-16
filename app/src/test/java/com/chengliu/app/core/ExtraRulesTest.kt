package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 附加动作与圆形倒计时的判定用例。
 *
 * 这里锁住的是「宁可漏点，不可误点」：青少年弹窗必须有提示语且按钮唯一，
 * 微信原图只在微信包名下认，圆形倒计时必须同时满足角落 / 尺寸 / 语义 / 可点。
 */
class ExtraRulesTest {

    private val screenW = 1000
    private val screenH = 2000
    private val allOn = ExtraRules.Flags(teenPopup = true, wechatOriginal = true)
    private val allOff = ExtraRules.Flags()

    private fun node(
        text: String,
        left: Int = 400,
        top: Int = 900,
        right: Int = 600,
        bottom: Int = 960,
        id: String = "",
        desc: String = "",
        clickable: Boolean = false,
    ) = ExtraRules.Node(text, desc, id, left, top, right, bottom, clickable)

    /** 右上角 80x80 的近正方形小按钮，典型圆形倒计时位置。 */
    private fun corner(
        text: String = "3",
        id: String = "tv.danmaku.bili:id/count_down",
        clickable: Boolean = true,
    ) = ExtraRules.Node(text, "", id, 880, 100, 960, 180, clickable)

    private fun match(
        nodes: List<ExtraRules.Node>,
        pkg: String = "com.example.app",
        flags: ExtraRules.Flags = allOn,
    ) = ExtraRules.match(pkg, nodes, screenW, screenH, flags)

    // ---------------- 青少年模式弹窗 ----------------

    @Test
    fun teenPopupNeedsHintAndSingleDismissButton() {
        val nodes = listOf(node("青少年模式提醒", right = 900), node("我知道了"))
        val hit = match(nodes)
        assertEquals(ExtraRules.Kind.TEEN_POPUP, hit?.kind)
        assertEquals(1, hit?.index)
        assertEquals("我知道了", hit?.label)
    }

    @Test
    fun teenPopupWithoutHintIsIgnored() {
        // 普通对话框里「我知道了」遍地都是，没有提示语绝不能动手。
        assertNull(match(listOf(node("版本更新说明"), node("我知道了"))))
    }

    @Test
    fun teenPopupWithTwoDismissCandidatesIsIgnored() {
        val nodes = listOf(
            node("青少年模式"),
            node("我知道了"),
            node("关闭"),
        )
        assertNull(match(nodes))
    }

    @Test
    fun teenPopupAcceptsSafeRefusalButton() {
        // 白名单里的「暂不开启」是安全的拒绝按钮，别被「开启」关键词误拦。
        val hit = match(listOf(node("青少年模式"), node("暂不开启")))
        assertEquals(ExtraRules.Kind.TEEN_POPUP, hit?.kind)
        assertEquals("暂不开启", hit?.label)
    }

    @Test
    fun teenPopupRejectsWideOrOffSwitch() {
        val wide = listOf(node("青少年模式"), node("我知道了", left = 100, right = 900))
        assertNull(match(wide))
        assertNull(match(wide, flags = allOff))
        assertNull(match(listOf(node("青少年模式"), node("我知道了")),
            flags = ExtraRules.Flags(teenPopup = false)))
    }

    @Test
    fun teenPopupReadsContentDescription() {
        val nodes = listOf(node("", desc = "青少年模式说明"), node("", desc = "知道了"))
        val hit = match(nodes)
        assertEquals("知道了", hit?.label)
    }

    // ---------------- 微信查看原图 ----------------

    @Test
    fun wechatOriginalOnlyMatchesWechatPackage() {
        val nodes = listOf(node("查看原图"))
        val hit = match(nodes, pkg = ExtraRules.WECHAT_PKG)
        assertEquals(ExtraRules.Kind.WECHAT_ORIGINAL, hit?.kind)
        assertNull(match(nodes, pkg = "com.example.gallery"))
    }

    @Test
    fun wechatOriginalAcceptsSizeSuffixButNotLookalikes() {
        assertEquals(
            "查看原图（2.3MB）",
            match(listOf(node("查看原图（2.3MB）")), pkg = ExtraRules.WECHAT_PKG)?.label,
        )
        assertNull(match(listOf(node("查看原图并下载")), pkg = ExtraRules.WECHAT_PKG))
    }

    @Test
    fun wechatOriginalRejectsDuplicatesAndDisabledFlag() {
        val two = listOf(node("查看原图"), node("查看原图"))
        assertNull(match(two, pkg = ExtraRules.WECHAT_PKG))
        assertNull(match(listOf(node("查看原图")), pkg = ExtraRules.WECHAT_PKG,
            flags = ExtraRules.Flags(wechatOriginal = false)))
    }

    @Test
    fun flagsOffNeverProducesHit() {
        val nodes = listOf(node("青少年模式"), node("我知道了"), node("查看原图"))
        assertNull(match(nodes, pkg = ExtraRules.WECHAT_PKG, flags = allOff))
    }

    // ---------------- 圆形倒计时 ----------------

    @Test
    fun roundCountdownRecognizesCornerClockWithResourceId() {
        assertTrue(ExtraRules.isRoundCountdown(corner(), screenW, screenH))
        assertTrue(ExtraRules.isRoundCountdown(corner(text = "跳过", id = ""), screenW, screenH))
    }

    @Test
    fun roundCountdownNeedsCornerSizeAndSemantics() {
        // 屏幕正中、宽矩形、大控件、普通文字都不算。
        assertFalse(ExtraRules.isRoundCountdown(
            ExtraRules.Node("3", "", "count_down", 450, 950, 530, 1030, true), screenW, screenH))
        assertFalse(ExtraRules.isRoundCountdown(
            ExtraRules.Node("3", "", "count_down", 800, 100, 990, 150, true), screenW, screenH))
        assertFalse(ExtraRules.isRoundCountdown(
            ExtraRules.Node("3", "", "count_down", 600, 100, 980, 500, true), screenW, screenH))
        assertFalse(ExtraRules.isRoundCountdown(corner(text = "设置", id = ""), screenW, screenH))
    }

    @Test
    fun roundCountdownPureNumberRequiresClickableOrId() {
        // 正文里的序号不能被当成跳过按钮。
        assertFalse(ExtraRules.isRoundCountdown(corner(id = "", clickable = false), screenW, screenH))
        assertTrue(ExtraRules.isRoundCountdown(corner(id = "", clickable = true), screenW, screenH))
    }

    @Test
    fun roundCountdownHonorsSwitch() {
        ExtraRules.roundEnabled = false
        try {
            assertFalse(ExtraRules.isRoundCountdown(corner(), screenW, screenH))
        } finally {
            ExtraRules.roundEnabled = true
        }
    }

    @Test
    fun roundCountdownRejectsEmptyScreen() {
        assertFalse(ExtraRules.isRoundCountdown(corner(), 0, 0))
    }
}
