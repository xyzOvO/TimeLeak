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
    NavItem("overview", "运行概览", Glyph.LOOK, IconBlue, "今日拦截、查询、跳过与命中来源"),
    NavItem("blocklog", "拦截记录", Glyph.LOOK, IconRed, "拦下了谁、它是干什么的、为什么拦"),
    NavItem("rules", "过滤规则", Glyph.RULES, IconBlue, "内置规则与当前生效数量"),
    NavItem("upstream", "上游解析", Glyph.UPSTREAM, IconPurple, "没被拦下的查询交给谁"),
    NavItem("shield", "防泄露", Glyph.SHIELD, IconGreen, "加密出口、拦截绕过与自检"),
    NavItem("health", "运行状态自检", Glyph.SHIELD, IconGreen, "八项检查、异常恢复与 DNS 连通性测试"),
    NavItem("blocked", "拦截名单", Glyph.BLOCKED, IconTeal, "手动拉黑的域名"),
    NavItem("allowed", "放行名单", Glyph.ALLOWED, IconOrange, "误拦时在这里加回"),
    NavItem("subs", "规则订阅", Glyph.SUBSCRIBE, IconSteel, "导入公开规则地址"),
    NavItem("subguard", "订阅安全", Glyph.SUBSCRIBE, IconSteel, "来源标识、差异预览、异常检查与回滚"),
    NavItem("skipad", "跳过广告", Glyph.SKIP, IconGreen, "自动点掉开屏广告，拦截摇一摇跳转"),
    NavItem("apprules", "应用规则", Glyph.SKIP, IconGreen, "按应用开关、档位、保护名单与导入导出"),
    NavItem("observe", "安全观察", Glyph.LOOK, IconOrange, "只识别不点击，逐条查看命中详情"),
    NavItem("privacy", "隐私审计", Glyph.PRIVACY, IconGrey, "存储、端点、权限、备份与一键清理"),
    NavItem("about", "隐私与关于", Glyph.PRIVACY, IconGrey, "记录策略与能力边界"),
    NavItem("theme", "外观", Glyph.THEME, IconGrey),
)

/**
 * 首页固定 3×3 九宫格。新页面只进侧栏（侧栏可滚动），
 * 否则 800dp 屏会顶破之前量好的 695dp 视口线。
 */
val homeTiles = navItems.filter {
    it.key in setOf(
        "overview", "blocklog", "rules", "upstream", "shield",
        "blocked", "allowed", "subs", "skipad",
    )
}

fun navItem(key: String): NavItem = navItems.firstOrNull { it.key == key } ?: navItems.first()
