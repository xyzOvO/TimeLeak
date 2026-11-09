package com.chengliu.app.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.core.BlockHit

@Composable
fun HomeScreen(
    running: Boolean,
    error: String?,
    queries: Long,
    blocked: Long,
    bypass: Long,
    foreign: Boolean,
    recent: List<BlockHit>,
    onToggle: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val privateDns = remember { privateDnsMode(ctx) }
    val latest = recent.maxByOrNull { it.lastAt }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.bg)
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(14.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onOpenMenu() },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.MENU, ui.text, Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Time Leak", fontSize = 21.sp, fontWeight = FontWeight.Bold, color = ui.text)
                Text("及时当勉励，岁月不待人。", fontSize = 11.sp, color = ui.sub)
            }
            Spacer(Modifier.weight(1f))
            StatusPill(running)
        }

        Spacer(Modifier.height(14.dp))

        Box(Modifier.weight(1f)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {

                HeroCard(
                    blocked = blocked,
                    queries = queries,
                    bypass = bypass,
                    services = recent.size,
                    latestName = latest?.name,
                    latestCount = latest?.count ?: 0,
                    running = running,
                )

                Spacer(Modifier.height(14.dp))

                Text(
                    "功能",
                    fontSize = 11.sp,
                    color = ui.sub,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                )

                for (row in homeTiles.chunked(3)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { item ->
                            Tile(item, Modifier.weight(1f)) { onOpen(item.key) }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(6.dp))

                RecentCard(recent = recent, running = running, onOpenAll = { onOpen("blocklog") })

                val hints = buildList {
                    if (!error.isNullOrBlank()) add(error)
                    if (privateDns == "hostname") {
                        add("系统「私人 DNS」为严格模式：它走 853 端口、不经本地过滤。本应用已自动避让（不再接管系统 DNS）以保证解析正常，代价是系统查询不被本地过滤。需要过滤时请在系统设置改为「自动」或「关闭」。")
                    }
                    if (foreign) {
                        add("检测到非 DNS 流量进入本地通道：这个系统可能不支持「仅 DNS 路由」。若断网请先点下方按钮停止，并告知机型。")
                    }
                }
                if (hints.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    hints.forEach { hint ->
                        HintBar(hint)
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(96.dp))
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 26.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (running) ui.warn else ui.accent)
                    .clickable { onToggle() },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(
                    if (running) Glyph.STOP else Glyph.PLAY,
                    ui.onAccent,
                    Modifier.size(34.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusPill(running: Boolean) {
    val ui = LocalUi.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(ui.card)
            .padding(horizontal = 11.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(if (running) ui.active else ui.sub),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            if (running) "过滤中" else "未开启",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (running) ui.active else ui.sub,
        )
    }
}

@Composable
private fun HeroCard(
    blocked: Long,
    queries: Long,
    bypass: Long,
    services: Int,
    latestName: String?,
    latestCount: Long,
    running: Boolean,
) {
    val ui = LocalUi.current
    val summary = when {
        latestName != null -> "来自 $services 个服务，最近是「$latestName」（$latestCount 次）"
        blocked > 0 -> "明细只在内存里，已随上次退出清空；新的拦截会重新出现。"
        running -> "还没拦到东西。打开一个带广告的 App 试试。"
        else -> "启动后，这里会显示拦下了谁、它是干什么的。"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ui.card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                blocked.toString(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = ui.text,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "次拦截",
                fontSize = 12.sp,
                color = ui.sub,
                modifier = Modifier.padding(bottom = 7.dp),
            )
            Spacer(Modifier.weight(1f))
            if (blocked > 0) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(ui.bg.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text("已挡下", fontSize = 10.sp, color = ui.active)
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(summary, fontSize = 12.sp, color = ui.sub, lineHeight = 18.sp)

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(ui.line))
        Spacer(Modifier.height(12.dp))

        Row {
            MiniStat("查询", queries.toString())
            Spacer(Modifier.weight(1f))
            MiniStat("接管绕过", bypass.toString())
            Spacer(Modifier.weight(1f))
            MiniStat("识别服务", services.toString())
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String) {
    val ui = LocalUi.current
    Column {
        Text(label, fontSize = 10.sp, color = ui.sub)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = ui.text)
    }
}

@Composable
private fun RecentCard(recent: List<BlockHit>, running: Boolean, onOpenAll: () -> Unit) {
    val ui = LocalUi.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(ui.card)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("刚刚拦下", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = ui.accent)
            Spacer(Modifier.weight(1f))
            Text(
                "全部 ›",
                fontSize = 12.sp,
                color = ui.sub,
                modifier = Modifier.clickable { onOpenAll() },
            )
        }
        Spacer(Modifier.height(8.dp))

        if (recent.isEmpty()) {
            Text(
                if (running) "暂时没有新的拦截。这里只保留最近 60 个服务，且只在内存里。"
                else "过滤未开启，所以没有记录。",
                fontSize = 12.sp,
                color = ui.sub,
                lineHeight = 18.sp,
            )
        } else {
            recent.take(2).forEachIndexed { index, hit ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ui.line))
                }
                RecentRow(hit, onOpenAll)
            }
        }
    }
}

@Composable
private fun RecentRow(hit: BlockHit, onClick: () -> Unit) {
    val ui = LocalUi.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ServiceBadge(hit.name, hit.kind)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    hit.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = ui.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(6.dp))
                Text("×${hit.count}", fontSize = 11.sp, color = ui.sub)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                hit.what,
                fontSize = 11.sp,
                color = ui.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "为什么拦：" + hit.why,
                fontSize = 10.sp,
                color = ui.sub.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分类色块 + 首字，比纯文字列表更容易一眼分辨「广告」和「统计」。 */
@Composable
fun ServiceBadge(name: String, kind: com.chengliu.app.data.ServiceKind, size: Int = 34) {
    val tint = kindTint(kind)
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size / 3).dp))
            .background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            serviceInitial(name),
            fontSize = (size / 2.2f).sp,
            fontWeight = FontWeight.Bold,
            color = tint,
        )
    }
}

@Composable
private fun Tile(item: NavItem, modifier: Modifier, onClick: () -> Unit) {
    val ui = LocalUi.current
    Column(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(ui.card)
            .clickable { onClick() }
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        GlyphIcon(item.glyph, item.tint, Modifier.size(20.dp))
        Spacer(Modifier.height(9.dp))
        Text(
            item.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = ui.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun HintBar(text: String) {
    val ui = LocalUi.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ui.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row {
            Box(
                Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ui.warn),
            )
            Spacer(Modifier.width(12.dp))
            Text(text, fontSize = 12.sp, color = ui.warn, lineHeight = 18.sp)
        }
    }
}

private fun privateDnsMode(ctx: Context): String = try {
    Settings.Global.getString(ctx.contentResolver, "private_dns_mode") ?: ""
} catch (_: Exception) {
    ""
}
