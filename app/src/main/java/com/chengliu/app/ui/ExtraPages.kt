package com.chengliu.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.core.AppPrefs
import com.chengliu.app.core.BackupRestore
import com.chengliu.app.core.BeatResult
import com.chengliu.app.core.BeatStage
import com.chengliu.app.core.BlockLog
import com.chengliu.app.core.Confidence
import com.chengliu.app.core.Counters
import com.chengliu.app.core.DecisionAction
import com.chengliu.app.core.HealthAction
import com.chengliu.app.core.HealthChecker
import com.chengliu.app.core.HealthItem
import com.chengliu.app.core.HealthState
import com.chengliu.app.core.LocalVpnService
import com.chengliu.app.core.Observation
import com.chengliu.app.core.ObservationLog
import com.chengliu.app.core.PrivacyAudit
import com.chengliu.app.core.Reject
import com.chengliu.app.core.RejectLog
import com.chengliu.app.core.RecoveryBudget
import com.chengliu.app.core.RunStats
import com.chengliu.app.core.ScanBeat
import com.chengliu.app.core.ScanLog
import com.chengliu.app.core.ServiceRecovery
import com.chengliu.app.core.SkipAdState
import com.chengliu.app.core.SubCheck
import com.chengliu.app.core.SubGuard
import com.chengliu.app.core.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- 共用小组件（与 DetailScreen 风格一致，独立命名避免 private 冲突） ----------------

@Composable
internal fun ExCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
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
internal fun ExLabel(text: String) {
    val ui = LocalUi.current
    Text(text, fontSize = 12.sp, color = ui.accent, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(10.dp))
}

@Composable
internal fun ExNote(text: String, color: Color? = null) {
    val ui = LocalUi.current
    Text(text, fontSize = 12.sp, color = color ?: ui.sub, lineHeight = 19.sp)
}

@Composable
internal fun ExButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = ui.accent, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun ExSwitch(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, color = ui.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(note, fontSize = 11.sp, color = ui.sub, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun fmt(at: Long): String =
    if (at <= 0L) "无" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(at))

// ---------------- 运行概览：8 项指标 ----------------

@Composable
fun OverviewPage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val stats by RunStats.state.collectAsState()
    val counts by Counters.today.collectAsState()
    val running by VpnState.running.collectAsState()
    val a11y = HealthChecker.accessibilityEnabled(ctx)
    val prefs = remember { AppPrefs(ctx) }

    // Counters 与 RunStats 是两个 StateFlow，定时把前者汇总进后者，页面才不会停在上一次读数。
    LaunchedEffect(Unit) {
        while (true) {
            RunStats.refresh()
            delay(2000L)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        ExCard {
            ExLabel("当前运行状态")
            val text = buildString {
                append(if (running) "DNS 过滤：运行中" else "DNS 过滤：未运行")
                append(" · ")
                append(
                    when {
                        !prefs.skipAdEnabled -> "跳过广告：未开启"
                        !a11y -> "跳过广告：待系统授权"
                        !SkipAdState.connected -> "跳过广告：服务未生效"
                        prefs.observeMode -> "跳过广告：安全观察模式（不点击）"
                        else -> "跳过广告：自动执行"
                    },
                )
                append("\n说明库与规则全部随包内置；运行时不上传任何查询记录")
            }
            Text(text, fontSize = 13.sp, color = ui.text, lineHeight = 20.sp)
        }
        Spacer(Modifier.height(12.dp))

        val metrics = listOf(
            "今日拦截广告数量" to counts.second.toString(),
            "今日 DNS 查询数量" to counts.first.toString(),
            "开屏广告跳过次数" to stats.skipClicks.toString(),
            "DNS 拦截次数" to counts.second.toString(),
            "被放行的域名数量" to if (stats.allowedCapped) "≥${stats.allowedDomains}" else stats.allowedDomains.toString(),
            "今日观察记录" to stats.observed.toString(),
            "绕过接管次数" to stats.bypass.toString(),
            "统计日期" to stats.date,
        )
        // 固定两列 + 单行省略：之前单条 Row 在类别多时把最后一项挤成极窄宽度，
        // 不可见的多行把卡片撑出一大块空白，这里从布局上根治。
        metrics.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (name, value) ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ui.bg)
                            .padding(12.dp),
                    ) {
                        Text(name, fontSize = 11.sp, color = ui.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = ui.text,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
        }

        ExCard {
            ExLabel("最近一次跳过记录")
            val last = stats.lastSkip
            if (last == null) {
                ExNote("还没有跳过记录。开启跳过广告并在安全观察模式里确认规则后，这里会显示摘要。")
            } else {
                ExNote("时间：${fmt(last.at)}\n应用：${last.pkg}\n规则：${last.rule}\n" +
                    "判定：${last.level}（${last.score} 分）· ${last.kind}\n操作：${last.action}")
                Spacer(Modifier.height(6.dp))
                ExNote("为保护隐私，最近一次记录不保存按钮文字；完整界面内容只在内存观察记录里。")
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("过滤规则命中来源（今日次数）")
            if (stats.sources.isEmpty()) {
                ExNote("今天还没有拦截命中。每次拦截会按「内置规则 / 手动拦截 / 导入订阅」累计。")
            } else {
                stats.sources.toList().sortedByDescending { it.second }.forEach { (k, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(k, fontSize = 12.sp, color = ui.text, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$v 次", fontSize = 12.sp, color = ui.accent)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("口径说明")
            ExNote("「今日拦截广告数量」与「DNS 拦截次数」在当前 DNS-only 架构下是同一个数：" +
                "所有被本地直接应答 NXDOMAIN 的查询都记为拦截，包含广告、追踪与统计域名。\n" +
                "「被放行的域名数量」只统计命中放行规则的去重域名，不是全部正常查询；上限 5000 条，超过显示 ≥。\n" +
                "所有数字跨天自动归零，可在「隐私审计」里一键清理。")
        }
    }
}

// ---------------- 运行状态自检 ----------------

@Composable
fun HealthPage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    val scope = rememberCoroutineScope()
    var dnsResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var items by remember { mutableStateOf(HealthChecker.check(ctx, null)) }
    var testing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var budgetTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            items = HealthChecker.check(ctx, dnsResult)
            budgetTick++
            delay(2000L)
        }
    }

    fun act(item: HealthItem) {
        when (item.action) {
            HealthAction.VPN_PERMISSION -> {
                // 只有用户点了按钮才打开系统授权界面，自动恢复路径绝不弹窗。
                val i = runCatching { VpnService.prepare(ctx) }.getOrNull()
                if (i != null) runCatching { ctx.startActivity(i) }
                else message = "VPN 已经授权"
            }
            HealthAction.START_VPN -> {
                val ok = ServiceRecovery.tryRestartVpn(ctx, "用户手动恢复")
                message = if (ok) "已尝试恢复 DNS 服务" else "恢复未执行：可能已在运行、未授权，或本窗口恢复次数用尽"
            }
            HealthAction.ACCESSIBILITY -> runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            HealthAction.BATTERY -> runCatching {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            HealthAction.RELOAD_RULES -> {
                LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD)
                message = "已请求重新加载规则"
            }
            HealthAction.REFRESH_SUBS -> message = "请到「规则订阅」页点立即更新；失败原因会显示在这里"
            HealthAction.DNS_TEST -> {
                testing = true
                scope.launch {
                    dnsResult = HealthChecker.dnsTest()
                    items = HealthChecker.check(ctx, dnsResult)
                    testing = false
                }
            }
            HealthAction.NONE -> {}
        }
        items = HealthChecker.check(ctx, dnsResult)
    }

    Column(Modifier.fillMaxWidth()) {
        if (message.isNotEmpty()) {
            ExCard { ExNote(message, ui.warn) }
            Spacer(Modifier.height(12.dp))
        }
        ExCard {
            ExLabel("自检项目")
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(8.dp).height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                when (item.state) {
                                    HealthState.OK -> ui.active
                                    HealthState.WARN -> ui.warn
                                    HealthState.ERROR -> IconRed
                                },
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.title, fontSize = 13.sp, color = ui.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(item.detail, fontSize = 11.sp, color = ui.sub, lineHeight = 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(ui.bg)
                            .clickable { act(item) }
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        // 已经正常的项目不该再显示「去授权 / 关闭优化」这种自相矛盾的按钮
                        val okPermission = item.state == HealthState.OK &&
                            (item.action == HealthAction.VPN_PERMISSION ||
                                item.action == HealthAction.ACCESSIBILITY ||
                                item.action == HealthAction.BATTERY)
                        Text(
                            if (okPermission) "正常" else when (item.action) {
                                HealthAction.DNS_TEST -> if (testing) "测试中" else "测试"
                                HealthAction.ACCESSIBILITY -> "去设置"
                                HealthAction.BATTERY -> "关闭优化"
                                HealthAction.VPN_PERMISSION -> "去授权"
                                HealthAction.START_VPN -> "恢复服务"
                                HealthAction.RELOAD_RULES -> "重新加载"
                                HealthAction.REFRESH_SUBS -> "去刷新"
                                HealthAction.NONE -> "正常"
                            },
                            fontSize = 11.sp,
                            color = if (item.state == HealthState.OK) ui.sub else ui.accent,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            ExNote("DNS 连通性测试只做一次本机解析，不发送查询内容给第三方，结果不落盘。")
        }
        Spacer(Modifier.height(12.dp))

        val blocks by BlockLog.hits.collectAsState()
        ExCard {
            ExLabel("打不开某个网站时")
            ExNote("按顺序排查，每一步都对应一个能改的地方：")
            Spacer(Modifier.height(4.dp))
            ExNote("1. 这个域名是否被规则拦了 —— 去「拦截记录」看命中规则与它的用途说明；\n" +
                "2. 是不是误拦 —— 在「放行名单」加回该域名，立刻生效；\n" +
                "3. 上游通不通 —— 上面「DNS 连通性」显示不通时，到「上游解析」换模式或 DoH 地址。")
            Spacer(Modifier.height(8.dp))
            val last = blocks.firstOrNull()
            if (last == null) {
                ExNote("本次运行还没有拦截记录。如果网页打不开但这里也是空的，问题多半在上游或网络本身。")
            } else {
                ExNote("最近一次拦截：${last.domainText}", ui.warn)
                ExNote("命中：${last.kind.label} · ${last.count} 次 · ${last.origin}")
                ExNote("说明：" + last.what.ifEmpty { "说明库里还没有这一条，可在「拦截记录」页看到原始命中" })
            }
            Spacer(Modifier.height(6.dp))
            ExNote("DNS 只负责「域名解析成哪个 IP」。如果解析正常但页面仍然打不开，问题在连接或服务端，不在这层过滤。")
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("自动恢复边界")
            ExNote("· 30 分钟内最多尝试 3 次，两次间隔至少 60 秒，超预算就停手；\n" +
                "· VPN / 无障碍未授权时只提示，绝不自动弹系统授权窗口；\n" +
                "· 只重启本应用的 DNS 前台服务，无障碍服务无法后台自启，也不尝试重启；\n" +
                "· 用户主动关闭、撤销授权时不触发任何恢复。")
            Spacer(Modifier.height(8.dp))
            ExNote(RecoveryBudget.describe(ctx))
            Spacer(Modifier.height(8.dp))
            ExButton("重置恢复预算", { RecoveryBudget.reset(ctx); budgetTick++ })
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("异常时怎么处理")
            var boot by remember { mutableStateOf(prefs.bootRestore) }
            var notify by remember { mutableStateOf(prefs.healthNotify) }
            ExSwitch("开机启动恢复", "开机后若已授权且服务未运行，按恢复预算尝试一次", boot) {
                boot = it; prefs.bootRestore = it
            }
            Spacer(Modifier.height(10.dp))
            ExSwitch("异常提醒通知", "服务异常时最多发一条通知，30 分钟内不重复", notify) {
                notify = it; prefs.healthNotify = it
            }
        }
    }
}

// ---------------- 安全观察模式 ----------------

@Composable
fun ObservePage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    var observe by remember { mutableStateOf(prefs.observeMode) }
    var enabled by remember { mutableStateOf(prefs.skipAdEnabled) }
    val list by ObservationLog.items.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        ExCard {
            ExLabel("安全观察模式（核心功能）")
            ExSwitch(
                "只识别规则，不执行点击",
                "开启后完整识别跳过按钮、弹窗与广告类型，但不会点任何控件、不会按返回键",
                observe,
            ) { observe = it; prefs.observeMode = it }
            Spacer(Modifier.height(8.dp))
            ExNote(if (observe) {
                "当前：观察模式。确认下方命中详情没问题后，关掉本开关即切回自动执行。"
            } else {
                "当前：自动执行。建议先打开观察模式跑几个应用，确认规则没有误命中再保持自动执行。"
            }, if (observe) ui.warn else ui.active)
            if (!enabled) {
                Spacer(Modifier.height(8.dp))
                ExNote("跳过广告总开关尚未开启，观察模式也不会收到任何界面事件。", ui.warn)
            }
            Spacer(Modifier.height(8.dp))
            ExButton(if (enabled) "关闭跳过广告总开关" else "开启跳过广告总开关", {
                enabled = !enabled
                prefs.skipAdEnabled = enabled
            })
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("识别记录 · ${list.size} 条")
            ExNote("仅本次运行内存可见，退出即清空，不落盘、不进备份、不上传。观察模式下同一目标 4 秒内只刷新一次。")
            Spacer(Modifier.height(8.dp))
            if (list.isEmpty()) {
                ExNote("还没有识别记录。打开观察模式并启动任意带开屏广告的应用，这里会显示命中的文本、ID、可点击状态、规则与判定。" +
                    "如果一直是空的，往下看「扫描心跳」，那里会写清是没扫到、还是扫到了没认出来。")
            } else {
                list.forEach { o -> ObservationCard(o) }
            }
            Spacer(Modifier.height(8.dp))
            ExButton("清空识别记录", { ObservationLog.clear() })
        }
        Spacer(Modifier.height(12.dp))

        val beats by ScanLog.items.collectAsState()
        ExCard {
            ExLabel("扫描心跳 · 最近 ${beats.size} 条")
            ExNote("每次扫描都留一条，不管有没有命中。空白识别记录到底代表什么，看这里：" +
                "「节点树为空」是没拿到界面，「没有候选」是拿到了但都不像跳过按钮，" +
                "「被节流合并」是切应用太快。只存内存，最多 40 条，不记界面文字。")
            Spacer(Modifier.height(8.dp))
            if (beats.isEmpty()) {
                ExNote("还没有扫描记录。确认跳过广告总开关已开，然后从桌面冷启动任意带开屏的应用，这里会出现心跳。")
            } else {
                beats.forEach { b -> BeatRow(b) }
            }
            Spacer(Modifier.height(8.dp))
            ExButton("清空心跳记录", { ScanLog.clear() })
        }
        Spacer(Modifier.height(12.dp))

        val rejects by RejectLog.items.collectAsState()
        ExCard {
            ExLabel("为什么没跳过 · 最近 ${rejects.size} 条")
            ExNote("每次判定为「不点」时留一条：哪个应用、什么目标、判到哪一档、卡在哪。只存内存，最多 20 条，" +
                "退出即清空。开着自动执行也能看，不需要先切观察模式。")
            Spacer(Modifier.height(8.dp))
            if (rejects.isEmpty()) {
                ExNote("暂时没有「不点」的记录。要么还没遇到广告，要么都点掉了；" +
                    "如果确实看到广告却什么都没有，先看「运行状态自检」里的无障碍与总开关。")
            } else {
                rejects.forEach { r -> RejectRow(r) }
            }
            Spacer(Modifier.height(8.dp))
            ExButton("清空诊断记录", { RejectLog.clear() })
        }
    }
}

@Composable
private fun RejectRow(r: Reject) {
    val ui = LocalUi.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.bg)
            .padding(10.dp),
    ) {
        Text(
            "${r.appLabel} · ${fmt(r.at)}",
            fontSize = 11.sp,
            color = ui.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "目标：${r.target.ifEmpty { "(无文案)" }}",
            fontSize = 12.sp,
            color = ui.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "判定：${r.level.label} · ${r.kind.label} · ${r.action.label}",
            fontSize = 11.sp,
            color = if (r.level == Confidence.LOW) ui.sub else ui.warn,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "原因：${r.reason.ifEmpty { "未记录" }}",
            fontSize = 11.sp,
            color = ui.sub,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BeatRow(b: ScanBeat) {
    val ui = LocalUi.current
    val color = when (b.result) {
        BeatResult.HIT -> ui.active
        BeatResult.NO_TREE, BeatResult.EMPTY, BeatResult.POLICY -> ui.warn
        else -> ui.sub
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.bg)
            .padding(10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                b.appLabel,
                modifier = Modifier.weight(1f, fill = false),
                fontSize = 12.sp,
                color = ui.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(fmt(b.at), fontSize = 10.sp, color = ui.sub)
        }
        Text(
            buildString {
                if (b.stage != BeatStage.SKIPPED) append(b.stage.label)
                if (b.nodes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("节点 ${b.nodes}")
                }
                if (b.candidates > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("候选 ${b.candidates}")
                }
            }.ifEmpty { "未进入扫描" },
            fontSize = 10.sp,
            color = ui.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            b.result.label + if (b.note.isNotEmpty()) "：${b.note}" else "",
            fontSize = 11.sp,
            color = color,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ObservationCard(o: Observation) {
    val ui = LocalUi.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(ui.bg)
            .padding(12.dp),
    ) {
        Text("当前应用：${o.appLabel}", fontSize = 12.sp, color = ui.sub, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text(fmt(o.at) + " · " + o.pkg, fontSize = 10.sp, color = ui.sub, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text("识别到目标：", fontSize = 12.sp, color = ui.accent)
        Spacer(Modifier.height(4.dp))
        Text("文本：${o.text.ifEmpty { "(无)" }}", fontSize = 12.sp, color = ui.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("ID：${o.id.ifEmpty { "(无)" }}", fontSize = 12.sp, color = ui.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("可点击：${if (o.clickable) "是" else "否"}", fontSize = 12.sp, color = ui.text)
        Text("规则：${o.rule}", fontSize = 12.sp, color = ui.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Text("判定：${o.level.label}（${o.score} 分）· ${o.kind.label}", fontSize = 12.sp,
            color = when (o.level) {
                Confidence.HIGH -> ui.active
                Confidence.MID -> ui.warn
                Confidence.LOW -> ui.sub
            })
        Text(
            "操作：${if (o.action == DecisionAction.OBSERVE) "观察模式，未点击" else o.action.label}",
            fontSize = 12.sp,
            color = if (o.action == DecisionAction.OBSERVE) ui.warn else ui.text,
        )
        if (o.reasons.isNotEmpty()) {
            Spacer(Modifier.height(5.dp))
            Text("依据：" + o.reasons.takeLast(4).joinToString("；"), fontSize = 10.sp,
                color = ui.sub, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------- 隐私审计 ----------------

@Composable
fun PrivacyPage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    var message by remember { mutableStateOf("") }
    var confirmAll by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = BackupRestore.export(ctx)
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            message = "配置已导出（不含订阅正文、拦截记录与观察记录）"
        }.onFailure { message = "导出失败：${it.message}" }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = ctx.contentResolver.openInputStream(uri)?.use {
                it.bufferedReader(Charsets.UTF_8).readText()
            }.orEmpty()
            message = BackupRestore.import(ctx, text)
            refreshTick++
        }.onFailure { message = "恢复失败：${it.message}" }
    }

    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("清除全部本地数据", fontSize = 16.sp) },
            text = {
                Text(
                    "将清空配置、自定义名单、应用策略、订阅缓存、白名单、运行统计与拦截明细。" +
                        "此操作不可撤销，建议先导出配置备份。",
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PrivacyAudit.clearAll(ctx)
                    LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD)
                    confirmAll = false
                    refreshTick++
                    message = "全部本地数据已清空，配置已回到默认值"
                }) { Text("确认清空", color = IconRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAll = false }) { Text("取消", color = ui.sub) }
            },
        )
    }

    Column(Modifier.fillMaxWidth()) {
        if (message.isNotEmpty()) {
            ExCard { ExNote(message, ui.warn) }
            Spacer(Modifier.height(12.dp))
        }
        val dns = PrivacyAudit.dnsStatus(ctx)
        val scopeEntry = PrivacyAudit.vpnScope(ctx)
        ExCard {
            ExLabel("DNS 加密状态")
            Text(dns.value, fontSize = 14.sp, color = ui.text)
            Spacer(Modifier.height(6.dp))
            ExNote(dns.note)
            Spacer(Modifier.height(12.dp))
            ExLabel("VPN 流量范围")
            Text(scopeEntry.value, fontSize = 14.sp, color = ui.text)
            Spacer(Modifier.height(6.dp))
            ExNote(scopeEntry.note)
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("本地数据")
            refreshTick.let { }
            PrivacyAudit.storage(ctx).forEach { e ->
                Text("${e.title} · ${e.value}", fontSize = 12.sp, color = ui.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (e.note.isNotEmpty()) ExNote(e.note)
                Spacer(Modifier.height(6.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("联网端点（仅这些）")
            PrivacyAudit.endpoints(ctx).forEach { e ->
                Text("${e.title}：${e.value}", fontSize = 11.sp, color = ui.text,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                if (e.note.isNotEmpty()) ExNote(e.note)
                Spacer(Modifier.height(5.dp))
            }
            ExNote("最近一次联网：${fmt(prefs.lastNetworkAt)}（只记时间，不记内容）")
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("权限清单")
            PrivacyAudit.permissions(ctx).forEach { e ->
                Text("${e.title} · ${e.value}", fontSize = 12.sp, color = ui.text)
                if (e.note.isNotEmpty()) ExNote(e.note)
                Spacer(Modifier.height(5.dp))
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("配置备份与恢复")
            ExNote("导出为 JSON，只含配置与名单；不含订阅正文、拦截记录、观察记录、统计。导入走字段白名单，不执行未知字段。")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExButton("导出配置", { exportLauncher.launch("timeleak-backup.json") }, Modifier.weight(1f))
                ExButton("从备份恢复", { importLauncher.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("本地数据一键清理")
            ExButton("清理运行数据（统计 / 记录）", {
                PrivacyAudit.clearRuntime(ctx)
                BlockLog.clear()
                refreshTick++
                message = "运行数据已清空，配置与名单保持不动"
            }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            ExButton("清除全部本地数据", { confirmAll = true }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("规则回归")
            ExNote("在电脑上执行 python tools/regression/run.py，可用典型节点树样本离线回归：" +
                "命中 / 误命中正常按钮 / 重复点击 / 页面切换 / 倒计时结束 / 跨应用影响。" +
                "跑的是与 App 同一份 DecisionCore 代码，不需要真机。")
        }
    }
}

// ---------------- 订阅安全 ----------------

private data class Preview(val check: SubCheck, val content: String, val isGkd: Boolean)

@Composable
fun SubGuardPage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var previews by remember { mutableStateOf<Map<String, Preview>>(emptyMap()) }
    var tick by remember { mutableStateOf(0) }
    var autoApply by remember { mutableStateOf(prefs.subAutoApply) }

    fun apply(url: String, p: Preview) {
        val map = if (p.isGkd) prefs.skipSubCacheMap.toMutableMap()
        else prefs.subscriptionCacheMap.toMutableMap()
        map[url] = p.content
        if (p.isGkd) {
            prefs.skipSubCacheMap = map
            prefs.skipSubCache = map.values.joinToString("\n")
            prefs.skipSubAt = System.currentTimeMillis()
        } else {
            prefs.subscriptionCacheMap = map
            prefs.subscriptionCache = map.values.joinToString("\n")
            prefs.subUpdatedAt = System.currentTimeMillis()
        }
        SubGuard.commit(ctx, url, p.content, p.check)
        LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD)
        previews = previews - url
        tick++
        message = "已应用 ${p.check.sourceTag}：${p.check.ruleCount} 条规则"
    }

    fun checkUpdates() {
        loading = true
        previews = emptyMap()
        scope.launch {
            val result = LinkedHashMap<String, Preview>()
            var failed = 0
            withContext(Dispatchers.IO) {
                val targets = prefs.subscriptions.map { it to false } +
                    prefs.skipSubs.map { it to true }
                for ((url, isGkd) in targets) {
                    if (!SubGuard.isTrusted(prefs, url)) {
                        failed++
                        continue
                    }
                    var conn: HttpURLConnection? = null
                    try {
                        conn = URL(url).openConnection() as HttpURLConnection
                        conn.connectTimeout = 10000
                        conn.readTimeout = 30000
                        conn.requestMethod = "GET"
                        conn.instanceFollowRedirects = true
                        val code = conn.responseCode
                        if (code !in 200..299) { failed++; continue }
                        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        val previous = if (isGkd) prefs.skipSubCacheMap[url].orEmpty()
                        else prefs.subscriptionCacheMap[url].orEmpty()
                        val check = SubGuard.inspect(prefs, url, body, previous)
                        result[url] = Preview(check, body, isGkd)
                    } catch (e: Exception) {
                        failed++
                        prefs.subError = "$url：${e.javaClass.simpleName} ${e.message.orEmpty()}"
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
            prefs.lastNetworkAt = System.currentTimeMillis()
            if (failed == 0) prefs.subError = ""
            previews = result
            loading = false
            tick++
            message = "已检查 ${result.size} 个来源" +
                if (failed > 0) "，跳过/失败 $failed 个（未信任或网络异常）" else "，等待确认后应用"
            // 自动应用只针对「无阻断问题且已信任」的来源，且必须用户显式打开开关。
            if (autoApply) {
                result.filter { it.value.check.trusted && !it.value.check.blocked }
                    .forEach { (u, p) -> apply(u, p) }
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        if (message.isNotEmpty()) {
            ExCard { ExNote(message, ui.warn) }
            Spacer(Modifier.height(12.dp))
        }
        ExCard {
            ExLabel("订阅不无条件信任")
            ExNote("来源标识 · SHA-256 指纹 · 更新差异 · 异常规则检查 · 一键回滚。\n" +
                "公开订阅（GKD 或其它）的后续内容一律先预览再应用；有阻断问题的更新不会自动生效。")
            Spacer(Modifier.height(10.dp))
            ExSwitch("自动应用无异常更新", "关闭时每次更新都要手动点「应用」；打开后也只对已信任且无阻断问题的来源生效",
                autoApply) { autoApply = it; prefs.subAutoApply = it }
            Spacer(Modifier.height(10.dp))
            ExButton(if (loading) "正在检查…" else "检查更新并生成差异预览", { if (!loading) checkUpdates() },
                Modifier.fillMaxWidth())
            if (prefs.subError.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                ExNote("最近一次失败原因：${prefs.subError}", IconRed)
            }
        }
        Spacer(Modifier.height(12.dp))

        // 差异预览区
        previews.forEach { (url, p) ->
            ExCard {
                ExLabel("差异预览 · ${p.check.sourceTag}")
                ExNote(url, ui.sub)
                Spacer(Modifier.height(8.dp))
                Text("规则条数：${p.check.diff.before} → ${p.check.ruleCount} " +
                    "（新增 ${p.check.diff.added} / 移除 ${p.check.diff.removed}）",
                    fontSize = 12.sp, color = ui.text)
                Text("SHA-256：${p.check.sha256.take(24)}…", fontSize = 11.sp, color = ui.sub)
                if (p.check.diff.addedSamples.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    ExNote("新增示例：" + p.check.diff.addedSamples.joinToString(" / "))
                }
                if (p.check.diff.removedSamples.isNotEmpty()) {
                    ExNote("移除示例：" + p.check.diff.removedSamples.joinToString(" / "))
                }
                Spacer(Modifier.height(6.dp))
                if (p.check.issues.isEmpty()) {
                    ExNote("异常检查：通过", ui.active)
                } else {
                    p.check.issues.forEach { i ->
                        ExNote("${i.label}：${i.text}", if (i.blocking) IconRed else ui.warn)
                    }
                }
                Spacer(Modifier.height(8.dp))
                val canApply = p.check.trusted && !p.check.blocked
                ExButton(
                    when {
                        !p.check.trusted -> "来源未信任，不能应用"
                        p.check.blocked -> "存在阻断问题，不能应用"
                        else -> "确认应用这一版"
                    },
                    { if (canApply) apply(url, p) },
                    Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        ExCard {
            ExLabel("来源白名单与完整性")
            tick.let { }
            val all = (prefs.subscriptions + prefs.skipSubs).distinct()
            if (all.isEmpty()) ExNote("还没有订阅来源。")
            all.forEach { url ->
                val trusted = SubGuard.isTrusted(prefs, url)
                val meta = SubGuard.metaOf(prefs, url)
                val current = prefs.subscriptionCacheMap[url] ?: prefs.skipSubCacheMap[url] ?: ""
                val intact = SubGuard.verifyCurrent(ctx, url, current)
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(SubGuard.hostOf(url), fontSize = 12.sp, color = ui.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    Text(url, fontSize = 10.sp, color = ui.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    ExNote(
                        (if (trusted) "已信任" else "未信任") +
                            " · 指纹 ${meta?.sha256?.take(8) ?: "未记录"}" +
                            " · ${meta?.count ?: 0} 条 · ${fmt(meta?.at ?: 0L)}" +
                            " · 完整性 " + if (intact) "一致" else if (meta == null) "未校验" else "不一致",
                        if (!trusted || (meta != null && !intact)) ui.warn else null,
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExButton(if (trusted) "取消信任" else "信任此来源", {
                            if (trusted) SubGuard.untrust(prefs, url) else SubGuard.trust(prefs, url)
                            tick++
                        }, Modifier.weight(1f))
                        ExButton("回滚上一版", {
                            val old = SubGuard.rollback(ctx, url)
                            if (old == null) {
                                message = "没有可回滚的版本（需要先成功应用过一次）"
                            } else {
                                val isGkd = prefs.skipSubs.contains(url)
                                val map = (if (isGkd) prefs.skipSubCacheMap
                                else prefs.subscriptionCacheMap).toMutableMap()
                                map[url] = old
                                if (isGkd) {
                                    prefs.skipSubCacheMap = map
                                    prefs.skipSubCache = map.values.joinToString("\n")
                                } else {
                                    prefs.subscriptionCacheMap = map
                                    prefs.subscriptionCache = map.values.joinToString("\n")
                                }
                                LocalVpnService.send(ctx, LocalVpnService.ACTION_RELOAD)
                                tick++
                                message = "已回滚到上一版（${SubGuard.countRules(old)} 条）"
                            }
                        }, Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ExCard {
            ExLabel("异常规则检查口径")
            ExNote("阻断：脚本片段 / 本地文件引用 / 超长行 / 空规则 / 放行规则暴增 50 条以上 / 条数骤降过半。\n" +
                "提醒：条数暴涨 5 倍、内容不像域名名单。\n" +
                "回滚只恢复「用户确认应用过」的上一版，不会把来路不明的新内容当成好版本。")
        }
    }
}
