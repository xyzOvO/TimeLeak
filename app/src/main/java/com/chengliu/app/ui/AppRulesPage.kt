package com.chengliu.app.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.core.AppCatalog
import com.chengliu.app.core.AppEntry
import com.chengliu.app.core.AppPrefs
import com.chengliu.app.core.BackupRestore
import com.chengliu.app.core.PolicyMode
import com.chengliu.app.core.SkipPolicyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用规则：一个应用一行，开关、策略、保护名单集中在一处。
 *
 * 设计上的三条边界：
 *  1. **开关不是删除**。关掉只影响「要不要动手」，原来选的档位保留，
 *     重新打开不用再挑一遍。
 *  2. **保护名单优先于一切**。进了名单就是不自动点击，连摇一摇回退也不做。
 *  3. **DNS 不做按应用例外**。Time Leak 只接管 DNS，Android 的解析由系统
 *     统一发起，拿不到「这条查询是哪个 App 发的」；与其给一个不生效的开关，
 *     不如把事实写在界面上，并把「按域名放行」指给用户。
 */
@Composable
fun AppRulesPage() {
    val ui = LocalUi.current
    val ctx = LocalContext.current
    val prefs = remember { AppPrefs(ctx) }

    var apps by remember { mutableStateOf(emptyList<AppEntry>()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(12) }
    var policies by remember { mutableStateOf(prefs.appPolicies) }
    var skipOff by remember { mutableStateOf(prefs.appSkipOff) }
    var protected by remember { mutableStateOf(prefs.protectedApps) }
    var recent by remember { mutableStateOf(prefs.recentApps) }
    var editPkg by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.Default) { AppCatalog.list(ctx) }
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.openOutputStream(uri)?.use {
                it.write(BackupRestore.exportAppRules(ctx).toByteArray(Charsets.UTF_8))
            }
            message = "应用规则已导出（只含包名、档位、关闭与保护名单）"
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
            message = BackupRestore.importAppRules(ctx, text)
            policies = prefs.appPolicies
            skipOff = prefs.appSkipOff
            protected = prefs.protectedApps
        }.onFailure { message = "导入失败：${it.message}" }
    }

    fun labelOf(pkg: String): String =
        apps.firstOrNull { it.pkg == pkg }?.label
            // 预置表里有中文名（淘宝、抖音…），比 PackageManager 的英文名或空名可靠
            ?: SkipPolicyStore.displayName(ctx, pkg).ifEmpty { pkg }

    fun setSkip(pkg: String, on: Boolean) {
        val s = skipOff.toMutableSet()
        if (on) s.remove(pkg) else s.add(pkg)
        skipOff = s
        prefs.appSkipOff = s
        message = if (on) "已开启「${labelOf(pkg)}」的自动跳过" else "已关闭「${labelOf(pkg)}」的自动跳过（档位保留）"
    }

    fun setProtected(pkg: String, on: Boolean) {
        val s = protected.toMutableSet()
        if (on) s.add(pkg) else s.remove(pkg)
        protected = s
        prefs.protectedApps = s
        message = if (on) "已把「${labelOf(pkg)}」加入保护名单" else "已把「${labelOf(pkg)}」移出保护名单"
    }

    fun setMode(pkg: String, m: PolicyMode) {
        val map = policies.toMutableMap()
        map[pkg] = m.name
        policies = map
        prefs.appPolicies = map
        message = "「${labelOf(pkg)}」策略改为「${m.label}」"
    }

    fun resetMode(pkg: String) {
        val map = policies.toMutableMap()
        map.remove(pkg)
        policies = map
        prefs.appPolicies = map
        message = "「${labelOf(pkg)}」已恢复自动归类"
    }

    Column(Modifier.fillMaxWidth()) {
        if (message.isNotEmpty()) {
            ExCard { ExNote(message, ui.warn) }
            Spacer(Modifier.height(12.dp))
        }

        ExCard {
            ExLabel("最近使用")
            if (recent.isEmpty()) {
                ExNote("还没有记录。启用「跳过广告」的无障碍服务后，切到别的应用就会自动记下（只在本机，最多 8 个）。")
            } else {
                recent.chunked(2).forEach { pair ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        pair.forEach { pkg ->
                            Boxed(
                                label = labelOf(pkg),
                                sub = modeText(ctx, pkg, skipOff, protected),
                                onClick = { editPkg = pkg },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // 两个一组固定列宽：单个 Row 塞多项时最后一项会被挤成 0 宽，
                        // 文本按极窄宽度换行会把整张卡片撑高（拦截记录页踩过）。
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(2.dp))
                ExNote("只记包名，不记你在应用里做了什么。清除全部本地数据时会一起清空。")
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("已安装应用")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; limit = 12 },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("搜索应用名或包名") },
            )
            Spacer(Modifier.height(8.dp))

            val q = query.trim().lowercase()
            val filtered = if (q.isEmpty()) {
                apps
            } else {
                apps.filter { it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
            }
            val shown = filtered.take(limit)
            if (loading) {
                ExNote("正在读取本机应用列表…")
            } else if (shown.isEmpty()) {
                ExNote("没有匹配的应用。列表只包含有桌面入口的应用；系统组件不在这里出现。")
            } else {
                shown.forEach { app ->
                    AppRuleRow(
                        label = app.label,
                        pkg = app.pkg,
                        status = modeText(ctx, app.pkg, skipOff, protected),
                        on = !skipOff.contains(app.pkg) && !protected.contains(app.pkg),
                        locked = protected.contains(app.pkg),
                        onToggle = { setSkip(app.pkg, it) },
                        onClick = { editPkg = app.pkg },
                    )
                }
                if (filtered.size > shown.size) {
                    Spacer(Modifier.height(8.dp))
                    ExButton(
                        "再显示 ${minOf(20, filtered.size - shown.size)} 个（共 ${filtered.size} 个）",
                        { limit += 20 },
                        Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            ExNote("共 ${apps.size} 个可配置应用。没列出的系统组件不会被自动点击。")
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("应用规则导入导出")
            ExNote("只导出「策略档位 + 关闭列表 + 保护名单」，不含订阅、DNS 配置与任何记录。换手机、或把调好的清单给别人对照都用这份。")
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ExButton("导出应用规则", {
                    exportLauncher.launch("timeleak-app-rules.json")
                }, Modifier.weight(1f))
                ExButton("导入应用规则", {
                    importLauncher.launch(arrayOf("application/json", "*/*"))
                }, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(12.dp))

        ExCard {
            ExLabel("这里改不了 DNS 过滤")
            ExNote("Time Leak 只接管 DNS。Android 的域名解析由系统统一发起，「这条查询是哪个应用发的」拿不到，" +
                "所以按应用放行域名做不出来——做一个开关却不生效，比不做更糟。")
            Spacer(Modifier.height(6.dp))
            ExNote("要放行某个域名，去「放行名单」添加，它对所有应用同样生效；误拦时这也是最直接的处理方式。")
            Spacer(Modifier.height(6.dp))
            ExNote("按应用断网同理：本版本不接管完整流量，没有默认路由，做不到单独断某个应用的网。")
        }
    }

    editPkg?.let { pkg ->
        // 关掉 / 保护时回显「原本的档位」，不要显示成「不自动点击」误导用户
        val mode = SkipPolicyStore.baseMode(ctx, pkg)
        val isOff = skipOff.contains(pkg)
        val isProtected = protected.contains(pkg)
        val overridden = policies.containsKey(pkg)
        AlertDialog(
            onDismissRequest = { editPkg = null },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = {
                Text(
                    "${labelOf(pkg)} · 应用规则",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(pkg, fontSize = 10.5.sp, color = ui.sub)
                    Spacer(Modifier.height(10.dp))

                    ExSwitch(
                        "自动跳过",
                        if (isProtected) "保护名单里，先移出才能开启" else "关掉后这个应用完全不动手，档位保留",
                        checked = !isOff && !isProtected,
                        onChange = { if (!isProtected) setSkip(pkg, it) },
                    )
                    Spacer(Modifier.height(10.dp))
                    ExSwitch(
                        "保护名单",
                        "最优先的一档：不自动点击，也不做摇一摇回退",
                        checked = isProtected,
                        onChange = { setProtected(pkg, it) },
                    )

                    Spacer(Modifier.height(14.dp))
                    Text("策略档位", fontSize = 12.sp, color = ui.accent, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    PolicyMode.values().forEach { m ->
                        val selectable = !isOff && !isProtected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = selectable) { setMode(pkg, m) }
                                .padding(vertical = 6.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = mode == m, onClick = null, enabled = selectable)
                            Spacer(Modifier.width(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    m.label,
                                    fontSize = 13.sp,
                                    color = if (selectable) ui.text else ui.sub,
                                )
                                Text(
                                    m.desc,
                                    fontSize = 11.sp,
                                    color = ui.sub,
                                    lineHeight = 16.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    if (overridden) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "恢复自动归类",
                            fontSize = 12.sp,
                            color = ui.warn,
                            modifier = Modifier.clickable { resetMode(pkg) },
                        )
                    }
                    // DNS 过滤例外不在这个弹窗里：它做不出来（见页面底部的说明），
                    // 与其放一个不生效的开关，不如不放。
                }
            },
            confirmButton = {
                TextButton(onClick = { editPkg = null }) { Text("关闭", color = ui.accent) }
            },
        )
    }
}

/** 一行应用：点整行进详情，右侧开关直接开关自动跳过。 */
@Composable
private fun AppRuleRow(
    label: String,
    pkg: String,
    status: String,
    on: Boolean,
    locked: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                fontSize = 13.sp,
                color = ui.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                fontSize = 10.5.sp,
                color = if (on) ui.active else ui.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                pkg,
                fontSize = 9.5.sp,
                color = ui.sub,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = on, onCheckedChange = onToggle, enabled = !locked)
    }
}

/** 两列卡片里的单个格子，最近使用区用。 */
@Composable
private fun Boxed(
    label: String,
    sub: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ui.bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = ui.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            sub,
            fontSize = 10.sp,
            color = ui.sub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 状态文案：保护 > 关闭 > 档位。 */
private fun modeText(
    ctx: Context,
    pkg: String,
    skipOff: Set<String>,
    protected: Set<String>,
): String = when {
    protected.contains(pkg) -> "保护名单 · 不自动点击"
    skipOff.contains(pkg) -> "已关闭 · 档位保留"
    else -> "自动 · " + SkipPolicyStore.forPkg(ctx, pkg).mode.label
}
