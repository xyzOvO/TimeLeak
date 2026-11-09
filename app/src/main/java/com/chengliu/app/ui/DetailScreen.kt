package com.chengliu.app.ui

import android.content.Context
import com.chengliu.app.BuildConfig
import android.content.Intent
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.core.AppPrefs
import com.chengliu.app.core.BlockLog
import com.chengliu.app.core.CapturePlan
import com.chengliu.app.core.DohEndpoints
import com.chengliu.app.core.DohWatch
import com.chengliu.app.core.LeakTargets
import com.chengliu.app.core.LocalVpnService
import com.chengliu.app.core.Counters
import com.chengliu.app.core.Privacy
import com.chengliu.app.core.PrivateDns
import com.chengliu.app.core.RuleParser
import com.chengliu.app.core.SkipEngine
import com.chengliu.app.core.SkipSources
import com.chengliu.app.core.VpnState
import com.chengliu.app.core.normalizeDomain
import com.chengliu.app.data.BuiltinRules
import com.chengliu.app.data.RuleStore
import com.chengliu.app.data.RuleTier
import com.chengliu.app.data.ServiceCatalog
import com.chengliu.app.data.ServiceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 每个功能入口对应一个独立详情页。所有状态仍写在同一个 SharedPreferences，
 * 不建数据库、不落任何查询记录。
 */
@Composable
fun DetailScreen(section: String, onBack: () -> Unit) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    val scope = rememberCoroutineScope()
    val item = navItem(section)

    var builtin by remember { mutableStateOf(prefs.builtinEnabled) }
    var tier by remember { mutableStateOf(RuleTier.of(prefs.ruleTier)) }
    var blocked by remember { mutableStateOf(prefs.customBlocked.sorted()) }
    var allowed by remember { mutableStateOf(prefs.customAllowed.sorted()) }
    var subs by remember { mutableStateOf(prefs.subscriptions.sorted()) }
    var mode by remember { mutableStateOf(prefs.upstreamMode) }
    var ip by remember { mutableStateOf(prefs.upstreamIp) }
    var doh by remember { mutableStateOf(prefs.dohUrl) }
    var subCount by remember { mutableStateOf(RuleParser.parse(prefs.subscriptionCache).size) }

    var leakGuard by remember { mutableStateOf(prefs.leakGuard) }
    var stripEcs by remember { mutableStateOf(prefs.stripEcs) }
    var blockBypass by remember { mutableStateOf(prefs.blockBypass) }
    var checks by remember { mutableStateOf<List<LeakCheck.Result>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var leakText by remember { mutableStateOf(prefs.leakTargetsText) }
    var leakNote by remember { mutableStateOf(prefs.leakTargetNote) }
    var leakCount by remember { mutableStateOf(prefs.leakTargetIps.size) }
    var leakDraft by remember { mutableStateOf(prefs.leakTargetsText) }
    var showLeak by remember { mutableStateOf(false) }
    var leakSaving by remember { mutableStateOf(false) }
    var hitsVersion by remember { mutableStateOf(0) }
    val dohHits = remember(hitsVersion) { DohWatch.snapshot() }
    // 私人 DNS 状态只读，不需要监听；进页面时取一次就够（改系统设置要用户自己动手）
    val privateDnsState = remember(section) { PrivateDns.read(ctx) }
    // 与服务端用同一套算法算「现在到底接管了多少条」，免得界面与隧道对不上
    val capturePlan = remember(leakCount, privateDnsState) {
        CapturePlan.resolve(prefs.leakTargetIps, privateDnsState)
    }
    val captureCount = capturePlan.first.size
    val privateDnsDropped = capturePlan.second
    var blockLogOn by remember { mutableStateOf(prefs.blockLog) }
    val running by VpnState.running.collectAsState()
    val bypasses by Counters.bypass.collectAsState()
    val counts by Counters.today.collectAsState()

    var dialogKind by remember { mutableStateOf(-1) }
    var input by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    fun notifyService() {
        LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD)
    }

    fun refresh() {
        if (subs.isEmpty()) {
            message = "还没有订阅地址，先添加一个"
            return
        }
        refreshing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val parts = mutableListOf<String>()
                var ok = 0
                for (u in subs) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(u).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.requestMethod = "GET"
                        conn.instanceFollowRedirects = true
                        val code = conn.responseCode
                        if (code in 200..299) {
                            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            if (body.isNotBlank()) {
                                parts.add(body)
                                ok++
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        conn?.disconnect()
                    }
                }
                parts.joinToString("\n") to ok
            }
            val (merged, ok) = result
            prefs.subscriptionCache = merged
            subCount = RuleParser.parse(merged).size
            message = "更新完成：$ok/${subs.size} 个来源，共 $subCount 条规则"
            notifyService()
            refreshing = false
        }
    }

    if (dialogKind >= 0) {
        val title = when (dialogKind) {
            0 -> "添加拦截域名"
            1 -> "添加放行域名"
            else -> "添加订阅地址"
        }
        AlertDialog(
            onDismissRequest = { dialogKind = -1 },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (dialogKind == 2) "https:// 开头的规则地址" else "例如 ads.example.com") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = input.trim()
                    if (v.isNotEmpty()) {
                        if (dialogKind == 2) {
                            if (v.startsWith("http://") || v.startsWith("https://")) {
                                subs = (subs + v).distinct().sorted()
                                prefs.subscriptions = subs.toSet()
                                message = "订阅地址已添加，点「立即更新」生效"
                            } else {
                                message = "订阅地址需要以 http:// 或 https:// 开头"
                            }
                        } else {
                            val d = normalizeDomain(v)
                            if (d == null) {
                                message = "这不像一个域名，请检查"
                            } else if (dialogKind == 0) {
                                blocked = (blocked + d).distinct().sorted()
                                prefs.customBlocked = blocked.toSet()
                                notifyService()
                            } else {
                                allowed = (allowed + d).distinct().sorted()
                                prefs.customAllowed = allowed.toSet()
                                notifyService()
                            }
                        }
                    }
                    input = ""
                    dialogKind = -1
                }) { Text("添加", color = ui.accent) }
            },
            dismissButton = {
                TextButton(onClick = { dialogKind = -1 }) { Text("取消", color = ui.sub) }
            },
        )
    }

    if (showLeak) {
        AlertDialog(
            onDismissRequest = { if (!leakSaving) showLeak = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("自定义接管目标", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = leakDraft,
                        onValueChange = { leakDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp),
                        label = { Text("IP / 网段 / 域名 / 规则地址", fontSize = 12.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Note(LeakTargets.formatHint())
                    if (leakSaving) {
                        Spacer(Modifier.height(8.dp))
                        Note("正在拉取并解析，可能需要几秒…", ui.warn)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !leakSaving,
                    onClick = {
                        leakSaving = true
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { LeakTargets.refresh(leakDraft) }
                            prefs.leakTargetsText = leakDraft
                            prefs.leakTargetIps = r.ips.toSet()
                            prefs.leakTargetNote = r.note
                            leakText = leakDraft
                            leakNote = r.note
                            leakCount = r.ips.size
                            leakSaving = false
                            showLeak = false
                            message = r.note
                            LocalVpnService.send(ctx, LocalVpnService.ACTION_RESTART)
                        }
                    },
                ) { Text("保存并生效", color = ui.accent) }
            },
            dismissButton = {
                TextButton(enabled = !leakSaving, onClick = { showLeak = false }) {
                    Text("取消", color = ui.sub)
                }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ui.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .clickable { onBack() },
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.BACK, ui.text, Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(item.label, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = ui.text)
        }
        Text(
            item.caption,
            fontSize = 12.sp,
            color = ui.sub,
            modifier = Modifier.padding(start = 50.dp, top = 2.dp),
        )
        Spacer(Modifier.height(22.dp))

        if (message.isNotEmpty()) {
            Hint(message, ui.warn)
            Spacer(Modifier.height(12.dp))
        }

        when (section) {
            "blocklog" -> BlockLogPage(
                totalBlocked = counts.second,
                enabled = blockLogOn,
                onToggle = {
                    blockLogOn = it
                    prefs.blockLog = it
                    BlockLog.enabled = it
                    if (!it) BlockLog.clear()
                },
                onReset = {
                    Counters.clear()
                    BlockLog.clear()
                    message = "今日统计与本次运行的明细已清零"
                },
            )

            "rules" -> RulesPage(
                builtin = builtin,
                tier = tier,
                customCount = blocked.size,
                subCount = subCount,
                running = running,
                onBuiltinChange = {
                    builtin = it
                    prefs.builtinEnabled = it
                    notifyService()
                },
                onTierChange = {
                    tier = it
                    prefs.ruleTier = it.key
                    notifyService()
                },
            )

            "upstream" -> UpstreamPage(mode, ip, doh, ui, leakGuard,
                onModeChange = { mode = it; prefs.upstreamMode = it },
                onIpChange = { ip = it; prefs.upstreamIp = it },
                onDohChange = { doh = it; prefs.dohUrl = it },
            )

            "shield" -> ShieldPage(
                leakGuard = leakGuard,
                stripEcs = stripEcs,
                blockBypass = blockBypass,
                mode = mode,
                doh = doh,
                running = running,
                bypasses = bypasses,
                checks = checks,
                checking = checking,
                onLeakGuard = {
                    leakGuard = it
                    prefs.leakGuard = it
                    // 打开防泄露就等于选加密上游，否则开关名不副实
                    if (it && mode != "doh") {
                        mode = "doh"
                        prefs.upstreamMode = "doh"
                    }
                    notifyService()
                },
                onStripEcs = {
                    stripEcs = it
                    prefs.stripEcs = it
                },
                onBlockBypass = {
                    blockBypass = it
                    prefs.blockBypass = it
                    // 路由集合变了，只能重建隧道才生效
                    LocalVpnService.send(ctx, LocalVpnService.ACTION_RESTART)
                },
                onCheck = {
                    checking = true
                    checks = emptyList()
                    scope.launch {
                        checks = LeakCheck.run(ctx, prefs)
                        checking = false
                    }
                },
                onOpenPrivateDns = { openPrivateDns(ctx) },
                leakCount = leakCount,
                leakNote = leakNote,
                leakSaving = leakSaving,
                onEditTargets = {
                    leakDraft = leakText
                    showLeak = true
                },
                captureCount = captureCount,
                privateDns = privateDnsState,
                privateDnsDropped = privateDnsDropped,
                dohHits = dohHits,
                onCapture = { host ->
                    scope.launch {
                        val before = prefs.leakTargetIps
                        val ips = withContext(Dispatchers.IO) {
                            LeakTargets.captureHosts(before, listOf(host))
                        }
                        if (ips.size == before.size) {
                            // 域名解析不出地址（NXDOMAIN、或只有 IPv6 而本机没有 v6）时说清楚，
                            // 不要报一个「已接管（共 0 个地址）」让用户以为生效了
                            message = "没解析到 $host 的新地址，没有加入接管列表。可以稍后重试，或手动填它的 IP。"
                        } else {
                            prefs.leakTargetIps = ips.toSet()
                            val note = "已接管 $host（新增 ${ips.size - before.size} 个地址）"
                            prefs.leakTargetNote = note
                            // 同步写回编辑框，用户下次打开能看到这一条，也方便自己删
                            leakText = if (leakText.isBlank()) host else "${leakText.trimEnd()}\n$host"
                            prefs.leakTargetsText = leakText
                            leakNote = note
                            leakCount = ips.size
                            DohWatch.forget(host)
                            hitsVersion++
                            message = "$note。它的 443/853 会被丢弃：应用要么退回系统 DNS（被正常过滤），要么直接解析失败。"
                            LocalVpnService.send(ctx, LocalVpnService.ACTION_RESTART)
                        }
                    }
                },
                onForgetHits = {
                    DohWatch.clear()
                    hitsVersion++
                },
            )

            "blocked" -> ListPage(
                entries = blocked,
                empty = "还没有手动拦截的域名。误拦以外的广告域名，建议用订阅批量导入。",
                addLabel = "＋ 添加拦截域名",
                onAdd = { input = ""; dialogKind = 0 },
                onRemove = { d ->
                    blocked = blocked - d
                    prefs.customBlocked = blocked.toSet()
                    notifyService()
                },
            )

            "allowed" -> ListPage(
                entries = allowed,
                empty = "还没有自己添加的放行域名。某个站点被误拦时，把它的域名加到这里。内置另有 ${BuiltinRules.ALLOWED.size} 条防误拦放行，在「过滤规则」页可见，不在此列表、也不能删。",
                addLabel = "＋ 添加放行域名",
                onAdd = { input = ""; dialogKind = 1 },
                onRemove = { d ->
                    allowed = allowed - d
                    prefs.customAllowed = allowed.toSet()
                    notifyService()
                },
            )

            "subs" -> SubsPage(
                entries = subs,
                subCount = subCount,
                refreshing = refreshing,
                onAdd = { input = ""; dialogKind = 2 },
                onRefresh = { refresh() },
                onRemove = { u ->
                    subs = subs - u
                    prefs.subscriptions = subs.toSet()
                },
            )

            "about" -> AboutPage(onClear = {
                Counters.clear()
                message = "今日计数已清空"
            })

            "skipad" -> SkipAdPage(ctx = ctx, prefs = prefs, scope = scope)
        }

        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun BlockLogPage(
    totalBlocked: Long,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit,
) {
    val ui = LocalUi.current
    val hits by BlockLog.hits.collectAsState()
    val ranked = remember(hits) { hits.sortedByDescending { it.count } }
    val shown = ranked.take(12)
    val kindCounts = remember(hits) {
        ServiceKind.values().mapNotNull { kind ->
            val n = hits.filter { it.kind == kind }.sumOf { it.count }
            if (n > 0L) kind to n else null
        }
    }

    Card {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(fmt(totalBlocked), fontSize = 32.sp, fontWeight = FontWeight.Bold, color = ui.text)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.padding(bottom = 4.dp)) {
                Text("次拦截", fontSize = 12.sp, color = ui.sub)
                Text("今天", fontSize = 10.sp, color = ui.sub)
            }
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 4.dp)) {
                Text("${hits.size} 个服务", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ui.text)
                Text(
                    if (hits.isEmpty()) "等待拦截" else "本次运行",
                    fontSize = 10.sp,
                    color = ui.sub,
                )
            }
        }

        if (kindCounts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                kindCounts.forEach { (kind, n) ->
                    Box(
                        Modifier
                            .weight(n.toFloat())
                            .fillMaxHeight()
                            .background(kindTint(kind)),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                kindCounts.forEach { (kind, n) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(kindTint(kind)))
                        Spacer(Modifier.width(5.dp))
                        Text("${kind.label} ${fmt(n)}", fontSize = 10.sp, color = ui.sub)
                    }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                if (enabled) "还没有新的拦截。明细只从本次运行开始算，左边是今天的总次数；有 App 想连广告或统计服务时，这里会写清它是谁、想干什么。"
                else "拦截明细已关闭，只统计总次数。需要看原因就重新打开下面那个开关。",
                fontSize = 12.sp,
                color = ui.sub,
                lineHeight = 18.sp,
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "清零今日统计",
                fontSize = 11.sp,
                color = ui.accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onReset() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }

    if (shown.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text(
            "按次数排序 · 点开无需展开，原因直接写在下面",
            fontSize = 10.sp,
            color = ui.sub,
            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        )
        shown.forEachIndexed { index, hit ->
            if (index > 0) Spacer(Modifier.height(10.dp))
            ServiceCard(hit)
        }
        if (ranked.size > shown.size) {
            Spacer(Modifier.height(10.dp))
            Note("还有 ${ranked.size - shown.size} 个服务没显示，清空记录后会重新开始统计。")
        }
    }

    Spacer(Modifier.height(14.dp))
    Card {
        SectionLabel("这份记录怎么存的")
        Note("只放在内存里：不写磁盘、不上传、不留时间线。退出应用或停止过滤就清空，最多保留 ${BlockLog.LIMIT} 个服务，每个服务只记 3 个示例域名。")
        Spacer(Modifier.height(4.dp))
        ToggleLine(
            "记录拦截明细",
            "关掉后不再判断「谁被拦了」，只保留总次数，列表立刻清空。",
            enabled,
            onToggle,
        )
        ActionButton("清空这份记录", { BlockLog.clear() }, Modifier.fillMaxWidth(), onCard = true)
        Spacer(Modifier.height(8.dp))
        Note("说明库覆盖 ${ServiceCatalog.size()} 个域名后缀，内容取自各公司公开资料；没收录的域名会直接显示它命中的那条规则，并标出规则来自内置、手动还是订阅。")
    }
}

@Composable
private fun ServiceCard(hit: com.chengliu.app.core.BlockHit) {
    val ui = LocalUi.current
    val tint = kindTint(hit.kind)
    Card {
        Row {
            ServiceBadge(hit.name, hit.kind, 38)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        hit.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = ui.text,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("${hit.count} 次", fontSize = 12.sp, color = ui.sub)
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    "${hit.owner} · ${hit.kind.label} · ${hit.source.label}",
                    fontSize = 10.sp,
                    color = ui.sub,
                    maxLines = 1,
                )

                Spacer(Modifier.height(9.dp))
                LabeledLine("它做什么", hit.what, ui.text.copy(alpha = 0.9f))
                Spacer(Modifier.height(7.dp))
                Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(1.dp))
                            .background(tint),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "为什么拦：${hit.why}",
                        fontSize = 11.sp,
                        color = ui.sub,
                        lineHeight = 17.sp,
                    )
                }
                if (hit.domainText.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Text(hit.domainText, fontSize = 10.sp, color = ui.sub)
                }
            }
        }
    }
}

@Composable
private fun LabeledLine(label: String, value: String, color: Color) {
    val ui = LocalUi.current
    Column {
        Text(label, fontSize = 10.sp, color = ui.sub)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 12.sp, color = color, lineHeight = 18.sp)
    }
}

private fun fmt(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val sb = StringBuilder()
    for (i in s.indices) {
        if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
        sb.append(s[i])
    }
    return sb.toString()
}

@Composable
private fun RulesPage(
    builtin: Boolean,
    tier: RuleTier,
    customCount: Int,
    subCount: Int,
    running: Boolean,
    onBuiltinChange: (Boolean) -> Unit,
    onTierChange: (RuleTier) -> Unit,
) {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    // 规则在后台线程解析，页面得自己盯着它完成，否则会一直停在「正在载入」。
    var loaded by remember(tier) { mutableStateOf(RuleStore.isLoaded(tier.key)) }
    LaunchedEffect(tier, running) {
        var waited = 0
        while (!loaded && waited < 80) {
            delay(100)
            loaded = RuleStore.isLoaded(tier.key)
            waited++
        }
    }
    // 三档条数写在各自文件头里；载入完成后改用真实解析结果，两者差几条是重复项。
    val counts = remember(tier, loaded) {
        RuleTier.values().associateWith { RuleStore.metaCount(ctx, it.key) ?: 0 }
    }
    val loadedBlocked = if (loaded) RuleStore.blockedOf(tier.key).size else 0
    val loadedAllowed = if (loaded) RuleStore.allowedOf(tier.key).size else 0
    val builtinNow = when {
        !builtin -> 0
        loadedBlocked > 0 -> loadedBlocked
        else -> counts[tier] ?: 0
    }
    val allowNow = if (loadedAllowed > 0) loadedAllowed else BuiltinRules.ALLOWED.size

    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("内置规则", fontSize = 15.sp, color = ui.text)
                Spacer(Modifier.height(4.dp))
                Note("弱 / 中 / 强三档都随安装包带着，切换即用，不需要联网下载。")
            }
            Switch(checked = builtin, onCheckedChange = onBuiltinChange)
        }
    }

    Spacer(Modifier.height(16.dp))
    SectionLabel("拦截强度")
    RuleTier.values().forEach { t ->
        val on = t == tier
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (on) ui.accent.copy(alpha = 0.12f) else ui.card)
                .clickable(enabled = builtin) { if (!on) onTierChange(t) }
                .padding(start = 6.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        ) {
            RadioButton(
                selected = on,
                enabled = builtin,
                onClick = { if (!on) onTierChange(t) },
            )
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(t.label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = ui.text)
                    Spacer(Modifier.width(8.dp))
                    Text("${fmt((counts[t] ?: 0).toLong())} 条", fontSize = 11.sp, color = ui.sub)
                    if (t == RuleTier.MEDIUM) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ui.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text("推荐", fontSize = 9.sp, color = ui.accent)
                        }
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(t.desc, fontSize = 11.sp, color = ui.sub, lineHeight = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(8.dp))
    Card {
        Text("当前生效 ${fmt((builtinNow + customCount + subCount).toLong())} 条", fontSize = 15.sp, color = ui.text)
        Spacer(Modifier.height(10.dp))
        Note(
            "内置 ${if (builtin) "${tier.label} ${fmt(builtinNow.toLong())} 条" else "已关闭"}" +
                " · 放行 ${fmt(allowNow.toLong())} 条",
        )
        Spacer(Modifier.height(6.dp))
        Note("手动拦截 $customCount 条 · 订阅 $subCount 条")
        Spacer(Modifier.height(10.dp))
        Note("匹配按域名后缀进行：规则 ads.example.com 也会拦下 img.ads.example.com。放行始终优先于拦截。")
        Spacer(Modifier.height(8.dp))
        Note(
            "档位自带的放行条目是公开规则集里的误拦修正，恒定生效，关掉上面的开关也不失效。" +
                "切换档位立即生效；已经被系统缓存过的域名要等缓存过期才会重新查询。",
        )
        if (!loaded && builtin) {
            Spacer(Modifier.height(8.dp))
            Note("规则正在后台载入（强档约 9.6 万条），载入完成后自动生效。", ui.warn)
        }
        if (!running) {
            Spacer(Modifier.height(8.dp))
            Note("过滤未开启，档位选择会在下次开启时生效。", ui.warn)
        }
    }
}

@Composable
private fun UpstreamPage(
    mode: String,
    ip: String,
    doh: String,
    ui: UiColors,
    leakGuard: Boolean,
    onModeChange: (String) -> Unit,
    onIpChange: (String) -> Unit,
    onDohChange: (String) -> Unit,
) {
    if (leakGuard) {
        Card {
            Note("「防泄露」已开启，上游被强制为加密 DoH，下面选明文也只会在关闭该模式后生效。")
        }
        Spacer(Modifier.height(12.dp))
    }
    Card {
        val options = listOf(
            "system" to "跟随系统 DNS",
            "ip" to "指定上游 IP",
            "doh" to "加密 DoH",
        )
        options.forEach { (key, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable { onModeChange(key) },
            ) {
                RadioButton(selected = mode == key, onClick = { onModeChange(key) })
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 14.sp, color = ui.text)
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    if (mode == "ip" || mode == "doh") {
        Card {
            OutlinedTextField(
                value = if (mode == "ip") ip else doh,
                onValueChange = if (mode == "ip") onIpChange else onDohChange,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (mode == "ip") "例如 223.5.5.5" else "DoH 地址") },
            )
            Spacer(Modifier.height(10.dp))
            Note(
                if (mode == "ip") "输入即生效。留空会自动探测；填错会退回国内公共递归。"
                else "输入即生效。常见公共 DoH 已内置 IP，连接时不会再发明文解析；不在表内的地址仍需一次明文引导，防泄露页会标出来。",
            )
        }
        Spacer(Modifier.height(12.dp))
    }
    Card {
        Note("只有没被规则拦下的查询才会转发给上游。上游由你选择，本应用不预设、不代理、不上传任何查询内容。")
    }
}

@Composable
private fun ShieldPage(
    leakGuard: Boolean,
    stripEcs: Boolean,
    blockBypass: Boolean,
    mode: String,
    doh: String,
    running: Boolean,
    bypasses: Long,
    checks: List<LeakCheck.Result>?,
    checking: Boolean,
    onLeakGuard: (Boolean) -> Unit,
    onStripEcs: (Boolean) -> Unit,
    onBlockBypass: (Boolean) -> Unit,
    onCheck: () -> Unit,
    onOpenPrivateDns: () -> Unit,
    leakCount: Int,
    leakNote: String,
    leakSaving: Boolean,
    onEditTargets: () -> Unit,
    captureCount: Int,
    privateDns: PrivateDns.State,
    privateDnsDropped: List<String>,
    dohHits: List<DohWatch.Hit>,
    onCapture: (String) -> Unit,
    onForgetHits: () -> Unit,
) {
    val ui = LocalUi.current
    val dohHost = remember(doh) {
        try {
            URL(doh).host ?: ""
        } catch (_: Exception) {
            ""
        }
    }
    val known = remember(dohHost) { DohEndpoints.isKnown(dohHost) }

    Card {
        SectionLabel("出口状态")
        StatLine(
            title = "上游解析",
            value = if (leakGuard) "强制加密 DoH · ${dohHost.ifEmpty { "地址无效" }}"
            else if (mode == "doh") "加密 DoH · ${dohHost.ifEmpty { "地址无效" }}" else "明文 · ${modeText(mode)}",
            color = if (leakGuard || mode == "doh") ui.active else ui.warn,
        )
        StatLine(
            title = "明文引导",
            value = when {
                dohHost.isEmpty() -> "DoH 地址无效，转发会直接失败"
                known -> "不需要，内置了该地址的 IP，连接全程无明文查询"
                else -> "需要一次明文解析，这一项在当前地址上无法消除"
            },
            color = if (known) ui.active else IconOrange,
        )
        StatLine(
            title = "绕过硬编码",
            value = if (blockBypass) "已接管 $bypasses 次" else "未开启，应用可以直接连公共 DNS",
            color = if (blockBypass) ui.active else ui.warn,
        )
        StatLine(
            title = "接管范围",
            value = if (blockBypass) "$captureCount 条路由（IPv4 + IPv6 各一套）"
            else "总开关关闭，只接管虚拟 DNS",
            color = if (blockBypass) ui.active else ui.sub,
        )
        StatLine(
            title = "过滤服务",
            value = if (running) "运行中" else "未开启，下面的改动要等启动后才生效",
            color = if (running) ui.active else ui.sub,
        )
    }

    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("防护项")
        ToggleLine(
            "防泄露模式",
            "忽略上面的明文上游，一律走加密 DoH；连不上就失败，不回退明文。",
            leakGuard,
            onLeakGuard,
        )
        ToggleLine(
            "剥离 ECS",
            "转发前删掉 EDNS Client Subnet，上游无法把你的位置还原到区县。",
            stripEcs,
            onStripEcs,
        )
        ToggleLine(
            "接管硬编码 DNS",
            "把常见公共 DNS 地址纳入隧道。改动会重建一次隧道，网络中断约一秒。",
            blockBypass,
            onBlockBypass,
        )
    }

    Spacer(Modifier.height(12.dp))
    Card(modifier = Modifier.clickable { onEditTargets() }) {
        SectionLabel("自定义接管目标")
        StatLine(
            title = "已生效",
            value = when {
                !blockBypass -> "接管总开关已关闭，自定义目标暂不生效"
                leakCount == 0 ->
                    "还没有自定义目标；内置已覆盖 ${Privacy.BYPASS_DNS.size} 个 v4 与 " +
                        "${Privacy.BYPASS_DNS6.size} 个 v6 公共 DNS"
                else -> "自定义 $leakCount 个地址"
            },
            color = if (blockBypass && leakCount > 0) ui.active else ui.sub,
        )
        if (leakNote.isNotEmpty()) {
            Note(leakNote)
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (leakSaving) "处理中…" else "编辑自定义目标 ›",
            fontSize = 13.sp,
            color = ui.accent,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(8.dp))
        Note("用来补齐内置表没覆盖的 DNS 服务器，或导入一份 IP 规则。保存后会重建隧道。")
    }

    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("自检")
        ActionButton(
            if (checking) "检测中…" else "开始自检",
            onCheck,
            Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        val done = checks
        when {
            done == null -> Note("发三条固定域名的探测查询，不含任何浏览记录，结果只留在本页。")
            done.isEmpty() -> Note("正在探测本机解析链路…")
            else -> done.forEachIndexed { index, r ->
                if (index > 0) Spacer(Modifier.height(10.dp))
                StatLine(
                    title = r.title,
                    value = r.detail,
                    color = when (r.level) {
                        LeakCheck.PASS -> ui.active
                        LeakCheck.NOTICE -> IconOrange
                        else -> ui.warn
                    },
                )
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("系统私人 DNS")
        StatLine(
            title = "当前状态",
            value = when (privateDns.mode) {
                PrivateDns.Mode.OFF -> "关闭"
                PrivateDns.Mode.AUTO -> "自动（尝试 DoT，失败回退明文）"
                PrivateDns.Mode.STRICT -> "指定服务器 · ${privateDns.specifier.ifEmpty { "未知" }}"
                PrivateDns.Mode.UNKNOWN -> "读不到系统设置"
            },
            color = when (privateDns.mode) {
                PrivateDns.Mode.OFF -> ui.active
                PrivateDns.Mode.AUTO -> ui.active
                PrivateDns.Mode.STRICT -> ui.warn
                else -> IconOrange
            },
        )
        Spacer(Modifier.height(8.dp))
        when (privateDns.mode) {
            PrivateDns.Mode.STRICT -> {
                Note(
                    "检测到严格模式：系统把全部查询走 DoT 到「${privateDns.specifier.ifEmpty { "指定服务器" }}」，" +
                        "这是加密通道，本应用看不见内容，按设计不经本地过滤。",
                    ui.sub,
                )
                Spacer(Modifier.height(8.dp))
                Note(
                    "已主动避让：本应用不再接管系统 DNS，改由系统直接走 DoT，避免整机解析失败。" +
                        "代价是系统级查询不再被本地过滤。需要过滤时，请在系统设置把私人 DNS 改为「关闭」或「自动」。",
                    ui.warn,
                )
            }
            PrivateDns.Mode.AUTO -> {
                Note(
                    "「自动」会先尝试对当前网络做 DoT，失败时回退到普通 DNS，因此仍会经过本应用的本地过滤，无需特殊处理。",
                    ui.sub,
                )
            }
            else -> {
                Note(PrivateDns.summary(privateDns), ui.sub)
            }
        }
        Spacer(Modifier.height(12.dp))
        ActionButton("打开系统私人 DNS 设置", onOpenPrivateDns, Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Note("这一项只能用系统设置改：写 private_dns_mode 需要系统级权限，普通应用拿不到。所以这里是「读状态 + 主动避让」，不是替用户改。")
    }

    if (dohHits.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Card {
            SectionLabel("疑似加密 DNS 域名")
            Note("这些名字看着就是应用自带的加密 DNS 服务器。它真正的 TLS 连接去的是表外地址，隧道按 IP 看不见；纳入接管后它的 443/853 会被丢弃 —— 应用要么退回系统 DNS（被正常过滤），要么直接解析失败。")
            Spacer(Modifier.height(4.dp))
            dohHits.forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(hit.name, fontSize = 13.sp, color = ui.text)
                        Spacer(Modifier.height(2.dp))
                        Text("出现 ${hit.count} 次", fontSize = 11.sp, color = ui.sub)
                    }
                    TextButton(onClick = { onCapture(hit.name) }) {
                        Text("接管", color = ui.accent, fontSize = 13.sp)
                    }
                }
            }
            TextButton(onClick = onForgetHits) {
                Text("清空记录", color = ui.sub, fontSize = 12.sp)
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("仍然覆盖不到的情况")
        Note("应用把加密 DNS 服务器的地址直接写死成 IP、从不去解析域名：DNS 层看不到这次解析，也就无从接管。可以在「自定义接管目标」里手工补上那个地址。")
        Spacer(Modifier.height(8.dp))
        Note("非 53 端口的自有协议（例如 DNS over QUIC）同理 —— 目标地址不在接管表里就看不见。")
    }
}

@Composable
private fun StatLine(title: String, value: String, color: Color) {
    val ui = LocalUi.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, color = ui.sub)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 13.sp, color = ui.text, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun ToggleLine(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = ui.text)
            Spacer(Modifier.height(3.dp))
            Note(note)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun modeText(mode: String): String = when (mode) {
    "ip" -> "指定上游 IP"
    "doh" -> "加密 DoH"
    else -> "跟随系统 DNS"
}

private fun openPrivateDns(ctx: Context) {
    if (Build.VERSION.SDK_INT < 28) return
    // 这个 action 没有公开常量，用的是系统 Settings 里的字符串值；
    // 个别 ROM 不认就退回无线设置页，不让按钮点了没反应。
    try {
        ctx.startActivity(
            Intent("android.settings.PRIVATE_DNS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun ListPage(
    entries: List<String>,
    empty: String,
    addLabel: String,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card {
        if (entries.isEmpty()) {
            Note(empty)
        } else {
            entries.forEach { d -> RuleRow(d) { onRemove(d) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    ActionButton(addLabel, onAdd)
    Spacer(Modifier.height(12.dp))
    Card {
        Note("改动会立刻通知正在运行的过滤服务，不需要重启。这里只保存域名文本，不记录任何访问行为。")
    }
}

@Composable
private fun SubsPage(
    entries: List<String>,
    subCount: Int,
    refreshing: Boolean,
    onAdd: () -> Unit,
    onRefresh: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Card {
        if (entries.isEmpty()) Note("还没有订阅地址。支持 hosts、AdGuard 的 ||域名^，以及 Clash / Subconverter 的 DOMAIN-SUFFIX 等常见格式。") else {
            entries.forEach { u -> RuleRow(u) { onRemove(u) } }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
        ActionButton("＋ 添加订阅", onAdd, Modifier.weight(1f))
        ActionButton(if (refreshing) "更新中…" else "立即更新", onRefresh, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Card {
        Note("已缓存 $subCount 条订阅规则。更新只在你手动点击时联网，不会后台自动同步。")
        Spacer(Modifier.height(8.dp))
        Note("内置规则里的公开名单部分取自 anti-AD（GPL-3.0），只取域名清单，不含其代码；其余为手工整理。订阅内容由原作者提供，权利归各自作者。")
    }
}

@Composable
private fun AboutPage(onClear: () -> Unit) {
    Card {
        SectionLabel("隐私")
        Note("查询过的域名不写入磁盘、不上传、不进系统日志。")
        Spacer(Modifier.height(8.dp))
        Note("「拦截记录」里的服务名和示例域名只在内存里保留，最多 ${BlockLog.LIMIT} 个服务，退出应用即清空，可以在那一页关掉或随时清空。")
        Spacer(Modifier.height(8.dp))
        Note("落盘的只有「今日查询数 / 今日拦截数 / 绕过接管数」三个整数，跨天自动归零。")
        Spacer(Modifier.height(8.dp))
        Note("无账号、无统计 SDK、无崩溃上报、无推送、无后台数据同步。")
        Spacer(Modifier.height(14.dp))
        ActionButton("清空今日计数", onClear)
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("能力边界")
        Note("接管系统 DNS 与常见公共 DNS 的 IPv4 / IPv6 明文查询，默认强制加密上游并剥离 ECS。")
        Spacer(Modifier.height(8.dp))
        Note("仍覆盖不到：应用把加密 DNS 地址写死成 IP 且从不解析域名、非 53 端口的自有协议，以及系统「私人 DNS」——最后一项只能用系统设置关，本应用会读状态并主动避让。")
        Spacer(Modifier.height(8.dp))
        Note("本版本不接管完整流量，因此不能按应用单独断网；该能力已预留扩展接口。")
        Spacer(Modifier.height(8.dp))
        Note("内置规则分弱 / 中 / 强三档，全部随安装包提供，在「过滤规则」页切换即可；订阅只是补充，不是必需。误拦时优先加进放行名单。")
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("关于")
        Note("Time Leak ${BuildConfig.VERSION_NAME} · 完全原创实现")
        Spacer(Modifier.height(8.dp))
        Note("未使用任何第三方项目的代码、名称、图标或服务端；界面与交互为独立设计。")
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ui.card)
            .then(modifier)
            .padding(16.dp),
        content = content,
    )
}

@Composable
private fun SectionLabel(text: String) {
    val ui = LocalUi.current
    Text(text, fontSize = 12.sp, color = ui.accent, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Note(text: String, color: Color? = null) {
    val ui = LocalUi.current
    Text(text, fontSize = 12.sp, color = color ?: ui.sub, lineHeight = 19.sp)
}

@Composable
private fun Hint(text: String, color: Color) {
    val ui = LocalUi.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ui.card)
            .padding(14.dp),
    ) { Text(text, fontSize = 12.sp, color = color, lineHeight = 18.sp) }
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onCard: Boolean = false,
) {
    val ui = LocalUi.current
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            // 放在卡片里时用页面底色，否则和卡片同色会看不见边界
            .background(if (onCard) ui.bg else ui.card)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = ui.accent, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RuleRow(text: String, onRemove: () -> Unit) {
    val ui = LocalUi.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Text(text, fontSize = 13.sp, color = ui.text, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Text(
            "移除",
            fontSize = 12.sp,
            color = ui.warn,
            modifier = Modifier.clickable { onRemove() },
        )
    }
}

/** 「上次更新」的人话描述。 */
private fun subTimeLabel(at: Long): String {
    if (at <= 0L) return "尚未拉取过规则"
    val min = (System.currentTimeMillis() - at) / 60000L
    return when {
        min < 1 -> "刚刚更新"
        min < 60 -> "${min} 分钟前更新"
        min < 60 * 24 -> "${min / 60} 小时前更新"
        else -> "${min / 1440} 天前更新"
    }
}

/** 检测本应用的无障碍服务是否已在系统设置里启用。 */
private fun isAccessibilityEnabled(ctx: Context): Boolean {
    val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    if (!am.isEnabled) return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any {
            it.resolveInfo.serviceInfo.packageName == ctx.packageName &&
                it.resolveInfo.serviceInfo.name.endsWith("SkipAdService")
        }
}

@Composable
private fun SkipAdPage(ctx: Context, prefs: AppPrefs, scope: kotlinx.coroutines.CoroutineScope) {
    val ui = LocalUi.current
    var enabled by remember { mutableStateOf(prefs.skipAdEnabled) }
    var builtin by remember { mutableStateOf(prefs.skipBuiltin) }
    var subs by remember { mutableStateOf(prefs.skipSubs.sorted()) }
    var subCount by remember { mutableStateOf(SkipEngine.groupCount()) }
    var refreshing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var accOn by remember { mutableStateOf(isAccessibilityEnabled(ctx)) }
    var showAdd by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    var shake by remember { mutableStateOf(prefs.shakeBlock) }
    var showCustom by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf(prefs.customRules) }
    var customCount by remember { mutableStateOf(SkipEngine.keywordCount() + SkipEngine.popupCount()) }
    var autoUpdate by remember { mutableStateOf(prefs.skipAutoUpdate) }
    var updatedLabel by remember { mutableStateOf(subTimeLabel(prefs.skipSubAt)) }
    var showSensorHelp by remember { mutableStateOf(false) }
    var skipCount by remember { mutableStateOf(prefs.skipToday) }
    var shakeCount by remember { mutableStateOf(prefs.shakeToday) }

    fun refresh(auto: Boolean = false) {
        if (refreshing) return
        val list = subs
        if (list.isEmpty()) {
            if (!auto) message = "还没有订阅地址"
            return
        }
        refreshing = true
        scope.launch {
            val parts = mutableListOf<String>()
            var ok = 0
            // 网络必须在 IO 线程：之前直接跑在主线程会抛 NetworkOnMainThreadException
            withContext(Dispatchers.IO) {
                for (u in list) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(u).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 15000
                        conn.requestMethod = "GET"
                        conn.instanceFollowRedirects = true
                        conn.setRequestProperty("User-Agent", "TimeLeak/${BuildConfig.VERSION_NAME}")
                        val code = conn.responseCode
                        if (code in 200..299) {
                            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                            if (body.isNotBlank()) {
                                parts.add(body)
                                ok++
                            }
                            android.util.Log.i("SkipAd", "订阅 $u -> HTTP $code, ${body.length} 字符")
                        } else {
                            android.util.Log.w("SkipAd", "订阅 $u -> HTTP $code")
                        }
                    } catch (ex: Exception) {
                        android.util.Log.w("SkipAd", "订阅 $u 失败: ${ex.javaClass.simpleName} ${ex.message}")
                    } finally { conn?.disconnect() }
                }
            }
            refreshing = false
            if (parts.isEmpty()) {
                if (!auto) message = "更新失败：连不上订阅源，换个网络再试"
                return@launch
            }
            val merged = parts.joinToString("\n")
            prefs.skipSubCache = merged
            prefs.skipSubAt = System.currentTimeMillis()
            updatedLabel = subTimeLabel(prefs.skipSubAt)
            // 解析 800KB 规则要几百毫秒，别压在主线程
            withContext(Dispatchers.Default) {
                SkipEngine.ensureLoaded(merged, prefs.customRules)
            }
            subCount = SkipEngine.groupCount()
            android.util.Log.i(
                "SkipAd",
                "规则合并 ${merged.length} 字符 -> ${subCount} 组 / ${SkipEngine.ruleCount()} 条",
            )
            message = if (auto) {
                "已自带 $subCount 组应用规则，开箱即用"
            } else {
                "更新完成：$ok/${list.size} 个来源，共 $subCount 组应用跳过规则"
            }
        }
    }

    // 首次进入：先把已有缓存解析出来，再看要不要联网更新
    LaunchedEffect(Unit) {
        // 老版本默认源里有国内连不通的地址，进页面时顺手换掉，免得用户一直看到「更新失败」
        val live = prefs.skipSubs.filterNot { SkipSources.isDeadSource(it) }
        if (live.size != prefs.skipSubs.size) {
            val fixed = (live + SkipSources.DEFAULT_SUBS).distinct()
            prefs.skipSubs = fixed.toSet()
            subs = fixed.sorted()
        }
        if (prefs.skipSubCache.isNotBlank() || prefs.customRules.isNotBlank()) {
            withContext(Dispatchers.Default) {
                SkipEngine.ensureLoaded(prefs.skipSubCache, prefs.customRules)
            }
            subCount = SkipEngine.groupCount()
            customCount = SkipEngine.keywordCount() + SkipEngine.popupCount()
        }
        if (prefs.skipAutoDue()) refresh(auto = true)
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("添加 GKD 订阅地址", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https:// 开头的 GKD dist 链接") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = input.trim()
                    if (v.startsWith("http://") || v.startsWith("https://")) {
                        subs = (subs + v).distinct().sorted()
                        prefs.skipSubs = subs.toSet()
                        message = "订阅地址已添加，点「立即更新」生效"
                    } else {
                        message = "订阅地址需要以 http:// 或 https:// 开头"
                    }
                    input = ""
                    showAdd = false
                }) { Text("添加", color = ui.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) { Text("取消", color = ui.sub) }
            },
        )
    }

    if (showSensorHelp) {
        AlertDialog(
            onDismissRequest = { showSensorHelp = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("从源头关掉摇一摇", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    Note("没有 root 的应用读不到别的 App 的传感器数据，所以我们做的是「跳到广告落地页就立刻退回」。想从源头断掉，可以关掉对应 App 的加速度传感器权限：")
                    Spacer(Modifier.height(8.dp))
                    Note("小米 / 澎湃：设置 → 应用设置 → 授权管理 → 应用权限管理 → 权限管理 → 获取加速度传感器信息，把常弹广告的 App 逐个关掉。")
                    Spacer(Modifier.height(6.dp))
                    Note("关掉后该 App 的横竖屏切换、摇一摇功能也会失效，按需取舍。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSensorHelp = false
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    } catch (_: Exception) {
                    }
                }) { Text("去应用设置", color = ui.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showSensorHelp = false }) { Text("知道了", color = ui.sub) }
            },
        )
    }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("自定义跳过规则", fontSize = 16.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column {
                    OutlinedTextField(
                        value = customInput,
                        onValueChange = { customInput = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        label = { Text("{\"keywords\":[\"跳过\"]} 或 popup_rules") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Note("可选。内置规则和订阅已经覆盖大部分 App，这里只用来补漏。支持 keywords / keywords_append / popup_rules；文字前加 + 是开头匹配、- 是结尾匹配、= 是完全相同，多个条件用 & 连接。")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.customRules = customInput
                    showCustom = false
                    scope.launch {
                        withContext(Dispatchers.Default) {
                            SkipEngine.ensureLoaded(prefs.skipSubCache, customInput)
                        }
                        customCount = SkipEngine.keywordCount() + SkipEngine.popupCount()
                        message = "已保存自定义规则，共 $customCount 条"
                    }
                }) { Text("保存", color = ui.accent) }
            },
            dismissButton = {
                TextButton(onClick = { showCustom = false }) { Text("取消", color = ui.sub) }
            },
        )
    }

    if (message.isNotEmpty()) {
        Hint(message, ui.warn)
        Spacer(Modifier.height(12.dp))
    }

    Card {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("今日")
            Spacer(Modifier.weight(1f))
            Text(
                "清零",
                fontSize = 12.sp,
                color = ui.sub,
                modifier = Modifier.clickable {
                    prefs.resetSkipStats()
                    skipCount = 0
                    shakeCount = 0
                },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$skipCount", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = ui.text)
                Note("已跳过广告")
            }
            Column(Modifier.weight(1f)) {
                Text("$shakeCount", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = ui.text)
                Note("拦下摇一摇")
            }
        }
        Spacer(Modifier.height(6.dp))
        Note("计数只存在本机，跨天自动归零。")
    }
    Spacer(Modifier.height(12.dp))

    Card {
        ToggleLine(
            title = "自动跳过开屏广告",
            note = if (accOn) "无障碍服务已授权，正在后台拦截" else "需在系统设置里授权无障碍服务",
            checked = enabled,
            onChange = { want ->
                enabled = want
                prefs.skipAdEnabled = want
                accOn = isAccessibilityEnabled(ctx)
                if (want && !accOn) {
                    try {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                        message = "请在「已安装的服务」里找到 Time Leak 跳过广告并开启"
                    } catch (_: Exception) {
                        message = "无法打开无障碍设置，请手动前往系统设置"
                    }
                }
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    Card {
        ToggleLine(
            title = "内置通用跳过词",
            note = "跳过 / Skip / 跳过广告 等常见按钮，无需订阅即可生效",
            checked = builtin,
            onChange = { builtin = it; prefs.skipBuiltin = it },
        )
    }
    Spacer(Modifier.height(12.dp))
    Card {
        ToggleLine(
            title = "拦截摇一摇跳转",
            note = "打开 App 时手机一晃就跳去浏览器/应用商店/电商？跳过去的一瞬间自动退回原应用。",
            checked = shake,
            onChange = { shake = it; prefs.shakeBlock = it },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "怎么从源头关掉 ›",
            fontSize = 12.sp,
            color = ui.accent,
            modifier = Modifier.clickable { showSensorHelp = true },
        )
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("规则来源")
        Note("内置广告 SDK 特征（穿山甲 / 优量汇 / 快手 / 百度等），跨应用通用，不需要任何订阅；下面是按 App 精调的订阅规则。")
        Spacer(Modifier.height(8.dp))
        if (subs.isEmpty()) {
            Note("当前没有订阅源，仅靠内置规则工作。")
        } else {
            subs.forEach { u ->
                RuleRow(u) { subs = (subs - u).also { prefs.skipSubs = it.toSet() } }
            }
        }
        Spacer(Modifier.height(6.dp))
        Note("$updatedLabel · 已解析 $subCount 组应用规则")
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActionButton("＋ 添加源", { showAdd = true }, Modifier.weight(1f))
        ActionButton(if (refreshing) "更新中…" else "立即更新", { refresh() }, Modifier.weight(1f))
    }
    Spacer(Modifier.height(12.dp))
    Card {
        ToggleLine(
            title = "每天自动更新规则",
            note = "打开这一页时检查一次，超过一天就静默拉取。只下载规则文本，不上传任何东西。",
            checked = autoUpdate,
            onChange = { autoUpdate = it; prefs.skipAutoUpdate = it },
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "恢复默认订阅源 ›",
            fontSize = 12.sp,
            color = ui.accent,
            modifier = Modifier.clickable {
                subs = SkipSources.DEFAULT_SUBS.sorted()
                prefs.skipSubs = subs.toSet()
                message = "已恢复默认订阅源，点「立即更新」生效"
            },
        )
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SectionLabel("自定义规则")
        Note("内置和订阅都没覆盖到的 App，可以在这里补一条。语法和主流跳过工具一致，但不需要安装它们。")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("编辑规则", { showCustom = true }, Modifier.weight(1f))
            ActionButton("清空", {
                prefs.customRules = ""
                customInput = ""
                scope.launch {
                    withContext(Dispatchers.Default) {
                        SkipEngine.ensureLoaded(prefs.skipSubCache, "")
                    }
                    customCount = 0
                    message = "已清空自定义规则"
                }
            }, Modifier.weight(1f))
        }
        if (customCount > 0) {
            Spacer(Modifier.height(6.dp))
            Note("当前 $customCount 条自定义规则生效中。")
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        Note("本能力通过 Android 无障碍服务实现，需要你授予「无障碍」权限。所有匹配都在你的手机上完成，不上传任何界面内容；今日计数只存在本机，不记录具体跳过什么。")
        Spacer(Modifier.height(6.dp))
        Note("小米 / 澎湃机型建议把 Time Leak 的省电策略设为「无限制」并允许自启动，否则后台被清理后无障碍服务会被系统挂起，跳过时好时坏。")
    }
}
