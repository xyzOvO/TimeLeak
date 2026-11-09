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
    var page by remember { mutableStateOf("home") }
    var showTheme by remember { mutableStateOf(false) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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
            else -> DetailScreen(section = page, onBack = { page = "home" })
        }
    }
}
