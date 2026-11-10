package com.chengliu.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.BuildConfig
import com.chengliu.app.core.AppPrefs

@Composable
fun AppDrawerContent(
    current: String,
    onNavigate: (String) -> Unit,
    onThemeClick: () -> Unit,
    onCheckUpdate: () -> Unit,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    // 侧栏也给一个状态标：不用进页面就能看出跳过广告到底开没开
    val skipStatus = rememberSkipAdStatus(prefs.skipAdEnabled)
    // 整块一起滚动：项目来源块排在导航之后，不钉在底部（内容多时随列表滑走）
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 26.dp),
    ) {
        Text("Time Leak", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ui.text)
        Spacer(Modifier.height(6.dp))
        Text("本地过滤 · 不留记录", fontSize = 12.sp, color = ui.sub)
        // 品牌短标记：用主色点一道，呼应主页启动按钮
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(ui.accent),
        )

        Spacer(Modifier.height(24.dp))
        // 按 key 分组，不再写死下标：以后加页面不会错位
        val groups = listOf(
            listOf("home", "overview", "blocklog", "rules"),
            listOf("upstream", "shield", "health", "subs", "subguard"),
            listOf("blocked", "allowed", "skipad", "apprules", "observe"),
        )
        groups.forEachIndexed { index, keys ->
            if (index > 0) DrawerDivider()
            for (key in keys) {
                val item = navItem(key)
                val badge = if (key == "skipad") skipStatus.status.shortLabel() else null
                val badgeColor = when (skipStatus.status) {
                    SkipAdStatus.RUNNING -> ui.active
                    SkipAdStatus.OFF -> ui.sub
                    else -> ui.warn
                }
                DrawerRow(item, current == item.key, { onNavigate(item.key) }, badge, badgeColor)
            }
        }
        DrawerDivider()
        val about = navItem("about")
        val privacy = navItem("privacy")
        DrawerRow(about, current == about.key, onClick = { onNavigate(about.key) })
        DrawerRow(privacy, current == privacy.key, onClick = { onNavigate(privacy.key) })
        DrawerDivider()
        DrawerRow(NavItem("update", "检查更新", Glyph.UPDATE, ui.sub), false, onClick = { onCheckUpdate() })
        DrawerRow(navItem("theme"), false, onClick = { onThemeClick() })

        Spacer(Modifier.height(20.dp))
        Text("不上传 · 无统计 · 拦截明细只留内存", fontSize = 11.sp, color = ui.sub)

        // 来源块：原来是固定页脚，现在跟在列表后面一起滚
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ui.line),
        )
        Spacer(Modifier.height(14.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("来自—xyzOvO", fontSize = 11.sp, color = ui.sub)
            Spacer(Modifier.height(6.dp))
            Text(
                "github.com/xyzOvO/TimeLeak",
                fontSize = 11.sp,
                color = ui.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(2.dp))
            Text("v${BuildConfig.VERSION_NAME}", fontSize = 10.sp, color = ui.sub.copy(alpha = 0.7f))
        }
        // 滚到底时留一点呼吸空间，不贴着屏幕边缘
        Spacer(Modifier.height(10.dp))
    }
}

/** 侧栏展示并跳转的项目地址。 */
private const val REPO_URL = "https://github.com/xyzOvO/TimeLeak"

@Composable
private fun DrawerRow(
    item: NavItem,
    selected: Boolean,
    onClick: () -> Unit,
    badge: String? = null,
    badgeColor: Color? = null,
) {
    val ui = LocalUi.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) ui.card else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp),
    ) {
        GlyphIcon(item.glyph, if (selected) ui.accent else item.tint, Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Text(
            item.label,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) ui.text else ui.text.copy(alpha = 0.86f),
        )
        if (badge != null) {
            Spacer(Modifier.weight(1f))
            Text(badge, fontSize = 10.sp, color = badgeColor ?: ui.sub)
        }
    }
}

@Composable
private fun DrawerDivider() {
    val ui = LocalUi.current
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(0.72f).height(1.dp).background(ui.line))
    Spacer(Modifier.height(12.dp))
}
