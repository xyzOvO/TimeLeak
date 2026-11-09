package com.chengliu.app.ui

import androidx.compose.ui.graphics.Color
import com.chengliu.app.data.ServiceKind

/** 分类配色。只用来区分「广告 / 统计 / 归因 / 上报」，不做装饰。 */
fun kindTint(kind: ServiceKind): Color = when (kind) {
    ServiceKind.AD -> IconRed
    ServiceKind.TRACK -> IconOrange
    ServiceKind.ATTRIB -> IconPurple
    ServiceKind.TELEMETRY -> IconSteel
    ServiceKind.OTHER -> IconGrey
}

/** 徽标里那个字：取服务名首字，比画一个意义不明的图标更好认。 */
fun serviceInitial(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    val c = t[0]
    return c.toString()
}
