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

    // ---------------- 安装弹窗拦截 ----------------

    private fun install(
        nodes: List<ExtraRules.Node>,
        w: Int = screenW,
    ) = ExtraRules.matchInstall(nodes, w)

    private fun cancelBtn(text: String = "取消", clickable: Boolean = true) =
        node(text, left = 300, top = 1200, right = 460, bottom = 1280, clickable = clickable)

    private fun confirmBtn(text: String = "安装") =
        node(text, left = 540, top = 1200, right = 700, bottom = 1280, clickable = true)

    @Test
    fun installPopupPicksCancel() {
        val hit = install(listOf(node("要安装此应用吗？", right = 900), confirmBtn(), cancelBtn()))
        assertEquals(ExtraRules.Kind.INSTALL_CANCEL, hit?.kind)
        assertEquals("取消", hit?.label)
    }

    @Test
    fun installPopupNeverPicksInstall() {
        // 只有「安装」没有「取消」时，一个都不点。
        assertNull(install(listOf(node("要安装此应用吗？", right = 900), confirmBtn())))
    }

    @Test
    fun installPopupRequiresConfirmButton() {
        // 普通对话框里的「取消」不能被当成安装弹窗。
        assertNull(install(listOf(node("确认退出登录？", right = 900), cancelBtn())))
    }

    @Test
    fun installPopupRejectsMultipleCancelCandidates() {
        val two = listOf(
            confirmBtn(),
            cancelBtn("取消"),
            cancelBtn("以后再说").copy(left = 300, top = 1400, right = 460, bottom = 1480),
        )
        assertNull(install(two))
    }

    @Test
    fun installPopupRejectsUnclickableTinyText() {
        // 不可点击、尺寸也不像按钮的说明文字不点。
        // （MIUI 的取消按钮是父容器 clickable、文字节点 clickable=false，所以不能再
        //  只凭 clickable 判断，改用尺寸兜底。）
        val tiny = node("取消", left = 300, top = 1200, right = 340, bottom = 1230, clickable = false)
        assertNull(install(listOf(confirmBtn(), tiny)))
    }

    // ---------------- MIUI 真机文案（2026-11-23 抓取，Redmi Note 12 Turbo 1080×2400）----------------
    //
    // 首次安装：继续安装 / 取消安装
    // 升级安装：继续更新 / 取消更新
    // 取消按钮本身是 TextView(clickable=false)，可点的是它外面那层 LinearLayout。

    private fun miuiCancel(text: String) =
        node(text, left = 560, top = 2160, right = 1000, bottom = 2300, clickable = false)

    private fun miuiConfirm(text: String) =
        node(text, left = 218, top = 2202, right = 382, bottom = 2257, clickable = false)

    @Test
    fun installPopupMatchesMiuiFirstInstall() {
        val nodes = listOf(
            node("行影集", left = 319, top = 310, right = 457, bottom = 372),
            miuiConfirm("继续安装"),
            miuiCancel("取消安装"),
        )
        val hit = install(nodes, w = 1080)
        assertEquals(ExtraRules.Kind.INSTALL_CANCEL, hit?.kind)
        assertEquals("取消安装", hit?.label)
    }

    @Test
    fun installPopupMatchesMiuiUpgrade() {
        val nodes = listOf(
            node("版本: 1.0.1 -> 1.0.9", left = 319, top = 402, right = 576, bottom = 444),
            miuiConfirm("继续更新"),
            miuiCancel("取消更新"),
        )
        val hit = install(nodes, w = 1080)
        assertEquals(ExtraRules.Kind.INSTALL_CANCEL, hit?.kind)
        assertEquals("取消更新", hit?.label)
    }

    @Test
    fun installPopupNeverPicksMiuiConfirm() {
        // 只有确认按钮、没有拒绝按钮时一个都不点（升级界面同理）。
        assertNull(install(listOf(miuiConfirm("继续更新")), w = 1080))
        assertNull(install(listOf(miuiConfirm("继续安装")), w = 1080))
    }

    @Test
    fun installPopupRejectsWideButton() {
        // 横跨大半屏的整行按钮不是对话框按钮。
        val wide = node("取消", left = 0, top = 1200, right = 900, bottom = 1280, clickable = true)
        assertNull(install(listOf(confirmBtn(), wide)))
    }

    @Test
    fun installPopupRejectsEmptyScreen() {
        assertNull(install(listOf(confirmBtn(), cancelBtn()), w = 0))
        assertNull(install(emptyList()))
    }
}
