package com.chengliu.app.ui

import androidx.compose.ui.graphics.Color
import com.chengliu.app.data.ServiceKind

/** 分类配色。区分用途；带「不是广告」含义的几类用冷色，跟广告的红色拉开。 */
fun kindTint(kind: ServiceKind): Color = when (kind) {
    ServiceKind.AD -> IconRed
    ServiceKind.TRACK -> IconOrange
    ServiceKind.ATTRIB -> IconPurple
    ServiceKind.TELEMETRY -> IconSteel
    ServiceKind.PCDN -> IconGreen
    ServiceKind.NONAD -> IconTeal
    ServiceKind.CDN -> IconBlue
    ServiceKind.FAMILY -> IconSteel
    ServiceKind.OTHER -> IconGrey
}

/** 徽标里那个字：取服务名首字，比画一个意义不明的图标更好认。 */
fun serviceInitial(name: String): String {
    val t = name.trim()
    if (t.isEmpty()) return "?"
    val c = t[0]
    return c.toString()
}
