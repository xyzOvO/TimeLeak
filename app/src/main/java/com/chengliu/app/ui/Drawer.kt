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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.BuildConfig

@Composable
fun AppDrawerContent(
    current: String,
    onNavigate: (String) -> Unit,
    onThemeClick: () -> Unit,
) {
    val ui = LocalUi.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.bg),
    ) {
        // 可滚动的主体：品牌头 + 导航。weight(1f) 把页脚顶到底部。
        Column(
            modifier = Modifier
                .weight(1f)
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
                listOf("home", "blocklog", "rules"),
                listOf("upstream", "shield"),
                listOf("blocked", "allowed", "subs", "skipad"),
            )
            groups.forEachIndexed { index, keys ->
                if (index > 0) DrawerDivider()
                for (key in keys) {
                    val item = navItem(key)
                    DrawerRow(item, current == item.key) { onNavigate(item.key) }
                }
            }
            DrawerDivider()
            val about = navItem("about")
            DrawerRow(about, current == about.key) { onNavigate(about.key) }
            DrawerRow(navItem("theme"), false) { onThemeClick() }

            Spacer(Modifier.height(20.dp))
            // 版本号统一走 BuildConfig，避免手写成「1.1.4」和关于页对不上
            Text(
                "Time Leak ${BuildConfig.VERSION_NAME} · 完全原创实现",
                fontSize = 11.sp,
                color = ui.sub,
            )
            Spacer(Modifier.height(6.dp))
            Text("不上传 · 无统计 · 拦截明细只留内存", fontSize = 11.sp, color = ui.sub)
        }

        // 底部固定页脚：最底居中标注来源与项目地址
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(ui.line),
            )
            Spacer(Modifier.height(12.dp))
            Text("来自—xyzOvO", fontSize = 11.sp, color = ui.sub)
            Spacer(Modifier.height(6.dp))
            val ctx = LocalContext.current
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
    }
}

/** 侧栏底部展示并跳转的项目地址。 */
private const val REPO_URL = "https://github.com/xyzOvO/TimeLeak"

@Composable
private fun DrawerRow(item: NavItem, selected: Boolean, onClick: () -> Unit) {
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
    }
}

@Composable
private fun DrawerDivider() {
    val ui = LocalUi.current
    Spacer(Modifier.height(12.dp))
    Box(Modifier.fillMaxWidth(0.72f).height(1.dp).background(ui.line))
    Spacer(Modifier.height(12.dp))
}
