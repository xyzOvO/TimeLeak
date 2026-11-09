package com.chengliu.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chengliu.app.core.AppPrefs
import com.chengliu.app.core.BlockLog
import com.chengliu.app.core.LocalVpnService
import com.chengliu.app.core.Counters
import com.chengliu.app.core.VpnState
import com.chengliu.app.ui.AppDrawerContent
import com.chengliu.app.ui.ChengliuTheme
import com.chengliu.app.ui.DetailScreen
import com.chengliu.app.ui.HomeScreen
import com.chengliu.app.ui.LocalUi
import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import com.chengliu.app.BuildConfig
import com.chengliu.app.core.UpdateChecker
import kotlinx.coroutines.Job
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var vpnLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Counters.init(this)

        vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                LocalVpnService.send(this, LocalVpnService.ACTION_START)
            } else {
                VpnState.setError("未获得 VPN 授权，过滤没有启动")
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }

        setContent {
            val prefs = remember { AppPrefs(this) }
            var themeMode by remember { mutableStateOf(prefs.themeMode) }
            ChengliuTheme(mode = themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = LocalUi.current.bg) {
                    AppRoot(
                        onToggle = ::toggleVpn,
                        themeMode = themeMode,
                        onThemeChange = {
                            themeMode = it
                            prefs.themeMode = it
                        },
                    )
                }
            }
        }
    }

    private fun toggleVpn() {
        if (VpnState.running.value) {
            LocalVpnService.send(this, LocalVpnService.ACTION_STOP)
            return
        }
        val consent = VpnService.prepare(this)
        if (consent != null) {
            vpnLauncher.launch(consent)
        } else {
            LocalVpnService.send(this, LocalVpnService.ACTION_START)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(
    onToggle: () -> Unit,
    themeMode: String,
    onThemeChange: (String) -> Unit,
) {
    val ui = LocalUi.current
    val context = LocalContext.current
    var page by remember { mutableStateOf("home") }
    var showTheme by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var updateUi by remember { mutableStateOf<UpdateUi>(UpdateUi.Hidden) }
    var updateJob by remember { mutableStateOf<Job?>(null) }

    // 关闭弹窗时若还有任务在跑，必须先取消，否则下一次进度回调会把弹窗又弹回来
    fun dismissUpdate() {
        updateJob?.cancel()
        updateJob = null
        updateUi = UpdateUi.Hidden
    }

    val running by VpnState.running.collectAsState()
    val error by VpnState.error.collectAsState()
    val foreign by VpnState.foreign.collectAsState()
    val counts by Counters.today.collectAsState()
    val bypass by Counters.bypass.collectAsState()
    val recent by BlockLog.hits.collectAsState()

    fun closeDrawer() {
        scope.launch { drawerState.close() }
    }

    fun navigate(key: String) {
        page = key
        closeDrawer()
    }

    fun checkUpdate() {
        updateJob?.cancel()
        updateJob = scope.launch {
            updateUi = UpdateUi.Checking
            val r = UpdateChecker.check()
            // 用户可能在检查期间关掉了弹窗：别把结果再写回去
            if (updateUi !is UpdateUi.Checking) return@launch
            updateUi = when (r) {
                is UpdateChecker.Result.Latest -> UpdateUi.Latest
                is UpdateChecker.Result.Available -> UpdateUi.Available(r.info)
                is UpdateChecker.Result.Error -> UpdateUi.Error(r.message)
            }
        }
    }

    fun downloadAndInstall(info: UpdateChecker.UpdateInfo) {
        val dest = File(context.cacheDir, "updates/update.apk")
        dest.parentFile?.mkdirs()
        updateJob?.cancel()
        updateJob = scope.launch {
            updateUi = UpdateUi.Downloading(0, 0)
            val ok = UpdateChecker.download(info.downloadUrls, dest) { done, total ->
                if (updateUi is UpdateUi.Downloading) updateUi = UpdateUi.Downloading(done, total)
            }
            if (updateUi !is UpdateUi.Downloading) return@launch
            if (!ok) {
                updateUi = UpdateUi.Error("下载失败，请检查网络后重试")
                return@launch
            }
            runCatching { dest.setReadable(true, false) }
            if (!UpdateChecker.signaturesMatch(context, dest.absolutePath)) {
                updateUi = UpdateUi.SignatureMismatch
                return@launch
            }
            // 多源兜底可能拿回旧包（例如 Release 附件还没更新），装上去只会失败，先明确告知
            val downloadedCode = UpdateChecker.archiveVersionCode(context, dest.absolutePath)
            if (downloadedCode >= 0 && downloadedCode <= BuildConfig.VERSION_CODE) {
                updateUi = UpdateUi.Error("下载到的仍是旧版本安装包，请稍后再试或到 GitHub 手动下载")
                return@launch
            }
            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", dest)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            updateUi = UpdateUi.Hidden
        }
    }

    BackHandler(enabled = drawerState.isOpen || page != "home") {
        if (drawerState.isOpen) closeDrawer() else page = "home"
    }

    if (showTheme) {
        AlertDialog(
            onDismissRequest = { showTheme = false },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = { Text("外观", fontSize = 16.sp) },
            text = {
                Column {
                    listOf(
                        "system" to "跟随系统",
                        "light" to "浅色",
                        "dark" to "深色",
                    ).forEach { (key, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable {
                                    onThemeChange(key)
                                    showTheme = false
                                },
                        ) {
                            RadioButton(
                                selected = themeMode == key,
                                onClick = {
                                    onThemeChange(key)
                                    showTheme = false
                                },
                            )
                            Text(label, fontSize = 14.sp, color = ui.text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTheme = false }) { Text("关闭", color = ui.accent) }
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = ui.bg,
                modifier = Modifier.width(280.dp),
            ) {
                AppDrawerContent(
                    current = page,
                    onNavigate = { navigate(it) },
                    onThemeClick = {
                        showTheme = true
                        closeDrawer()
                    },
                    onCheckUpdate = { checkUpdate() },
                )
            }
        },
    ) {
        when (page) {
            "home" -> HomeScreen(
                running = running,
                error = error,
                queries = counts.first,
                blocked = counts.second,
                bypass = bypass,
                foreign = foreign,
                recent = recent,
                onToggle = onToggle,
                onOpenMenu = { scope.launch { drawerState.open() } },
                onOpen = { page = it },
            )
            else -> DetailScreen(section = page, onBack = { page = "home" }, onCheckUpdate = { checkUpdate() })
        }
    }

    if (updateUi !is UpdateUi.Hidden) {
        val s = updateUi
        AlertDialog(
            onDismissRequest = { dismissUpdate() },
            containerColor = ui.card,
            titleContentColor = ui.text,
            textContentColor = ui.text,
            title = {
                Text(
                    when (s) {
                        is UpdateUi.Checking -> "检查更新"
                        is UpdateUi.Latest -> "已是最新"
                        is UpdateUi.Available -> "发现新版本 v${s.info.latestVersion}"
                        is UpdateUi.Downloading -> "下载中"
                        is UpdateUi.Error -> "更新失败"
                        is UpdateUi.SignatureMismatch -> "无法安装"
                        else -> ""
                    },
                    fontSize = 16.sp, fontWeight = FontWeight.Medium,
                )
            },
            text = {
                when (s) {
                    is UpdateUi.Checking -> Text("正在连接更新服务器…", fontSize = 13.sp, color = ui.sub)
                    is UpdateUi.Latest -> Text("当前已是最新版本 Time Leak ${BuildConfig.VERSION_NAME}", fontSize = 13.sp, color = ui.sub)
                    is UpdateUi.Available -> Column {
                        if (s.info.notes.isNotBlank()) {
                            Text(s.info.notes, fontSize = 12.sp, color = ui.sub, lineHeight = 19.sp)
                            Spacer(Modifier.height(10.dp))
                        }
                        Text("当前 ${BuildConfig.VERSION_NAME} → 最新 ${s.info.latestVersion}", fontSize = 12.sp, color = ui.accent)
                    }
                    is UpdateUi.Downloading -> Column {
                        val pct = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0
                        LinearProgressIndicator(
                            { if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${formatMB(s.done)} / ${formatMB(s.total)}  ($pct%)", fontSize = 12.sp, color = ui.sub)
                    }
                    is UpdateUi.Error -> Text(s.message, fontSize = 13.sp, color = ui.sub)
                    is UpdateUi.SignatureMismatch -> Text(
                        "下载的安装包与已安装版本签名不一致，可能来自非官方渠道，已停止安装。请从 GitHub 仓库 xyzOvO/TimeLeak 获取官方包。",
                        fontSize = 13.sp, color = ui.sub,
                    )
                    else -> {}
                }
            },
            confirmButton = {
                when (s) {
                    is UpdateUi.Available -> TextButton(onClick = { downloadAndInstall(s.info) }) {
                        Text("下载并安装", color = ui.accent)
                    }
                    is UpdateUi.Error -> TextButton(onClick = { checkUpdate() }) {
                        Text("重试", color = ui.accent)
                    }
                    is UpdateUi.Checking, is UpdateUi.Downloading ->
                        TextButton(onClick = { dismissUpdate() }) {
                            Text("取消", color = ui.sub)
                        }
                    else -> TextButton(onClick = { dismissUpdate() }) {
                        Text("关闭", color = ui.accent)
                    }
                }
            },
            dismissButton = if (s is UpdateUi.Available) {
                { TextButton(onClick = { dismissUpdate() }) { Text("稍后", color = ui.sub) } }
            } else null,
        )
    }
}

/** 应用内「检测更新」的弹窗状态机。 */
private sealed interface UpdateUi {
    object Hidden : UpdateUi
    object Checking : UpdateUi
    object Latest : UpdateUi
    data class Available(val info: UpdateChecker.UpdateInfo) : UpdateUi
    data class Downloading(val done: Long, val total: Long) : UpdateUi
    data class Error(val message: String) : UpdateUi
    object SignatureMismatch : UpdateUi
}

private fun formatMB(bytes: Long): String {
    if (bytes <= 0) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb < 1) "${bytes / 1024} KB" else String.format("%.1f MB", mb)
}
