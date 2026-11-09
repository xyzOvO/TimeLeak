package com.chengliu.app.ui

import androidx.compose.ui.graphics.Color

val IconBlue = Color(0xFF4A7BF7)
val IconRed = Color(0xFFE4574C)
val IconTeal = Color(0xFF21A79A)
val IconOrange = Color(0xFFE39A3C)
val IconPurple = Color(0xFF8B6FD6)
val IconSteel = Color(0xFF6688B8)
val IconGreen = Color(0xFF3FA37F)
val IconGrey = Color(0xFF8892A0)

data class NavItem(
    val key: String,
    val label: String,
    val glyph: Glyph,
    val tint: Color,
    val caption: String = "",
)

/** 首页卡片与侧栏共用同一份导航定义，避免两处文字走样。 */
val navItems = listOf(
    NavItem("home", "首页", Glyph.HOME, IconBlue),
    NavItem("blocklog", "拦截记录", Glyph.LOOK, IconRed, "拦下了谁、它是干什么的、为什么拦"),
    NavItem("rules", "过滤规则", Glyph.RULES, IconBlue, "内置规则与当前生效数量"),
    NavItem("upstream", "上游解析", Glyph.UPSTREAM, IconPurple, "没被拦下的查询交给谁"),
    NavItem("shield", "防泄露", Glyph.SHIELD, IconGreen, "加密出口、拦截绕过与自检"),
    NavItem("blocked", "拦截名单", Glyph.BLOCKED, IconTeal, "手动拉黑的域名"),
    NavItem("allowed", "放行名单", Glyph.ALLOWED, IconOrange, "误拦时在这里加回"),
    NavItem("subs", "规则订阅", Glyph.SUBSCRIBE, IconSteel, "导入公开规则地址"),
    NavItem("skipad", "跳过广告", Glyph.SKIP, IconGreen, "自动点掉开屏广告，拦截摇一摇跳转"),
    NavItem("about", "隐私与关于", Glyph.PRIVACY, IconGrey, "记录策略与能力边界"),
    NavItem("theme", "外观", Glyph.THEME, IconGrey),
)

val homeTiles = navItems.filter { it.key != "home" && it.key != "theme" }

fun navItem(key: String): NavItem = navItems.firstOrNull { it.key == key } ?: navItems.first()
