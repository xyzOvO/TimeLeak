package com.chengliu.app.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * 开机启动恢复：只在用户显式打开「开机恢复」、且 VPN 授权仍有效时尝试一次。
 * 恢复次数受 [RecoveryBudget] 约束；未授权只发一条提醒，绝不弹系统授权界面。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return
        val prefs = AppPrefs(ctx)
        if (!prefs.bootRestore) return
        if (runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false).not()) {
            HealthNotifier.notifyOnce(
                ctx, "boot_no_permission",
                "开机恢复未执行", "VPN 授权已失效，点开应用重新授权即可恢复本地过滤。",
            )
            return
        }
        Counters.init(ctx)
        RunStats.init(ctx)
        ServiceRecovery.tryRestartVpn(ctx, "开机恢复")
        // 无障碍服务由系统按授权自动绑定，这里不做任何后台启用尝试。
    }
}
