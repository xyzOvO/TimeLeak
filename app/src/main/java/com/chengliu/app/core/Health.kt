package com.chengliu.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.PowerManager
import android.provider.Settings
import com.chengliu.app.data.RuleStore
import com.chengliu.app.data.RuleTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 运行状态自检 + **有边界**的自动恢复。
 *
 * 边界是刻意写死的三条，改之前请想清楚：
 *  1. 30 分钟内最多尝试 3 次，两次之间至少 60 秒；超预算就彻底停手等下一个窗口。
 *  2. **绝不自动弹系统授权窗口**。VPN 未授权、无障碍未授权时只提示、只发通知，
 *     用户点进设置才算数。反复弹授权框是比服务挂掉更糟糕的体验。
 *  3. 只自动重启本应用的 DNS 前台服务；无障碍服务无法后台自启，也不尝试重启。
 */
enum class HealthState { OK, WARN, ERROR }

enum class HealthAction {
    NONE,
    VPN_PERMISSION,
    START_VPN,
    ACCESSIBILITY,
    BATTERY,
    RELOAD_RULES,
    REFRESH_SUBS,
    DNS_TEST,
}

data class HealthItem(
    val key: String,
    val title: String,
    val state: HealthState,
    val detail: String,
    val fix: String,
    val action: HealthAction,
)

object HealthChecker {

    /** 直接读系统设置实时值，不用带客户端缓存的 AccessibilityManager。 */
    fun accessibilityEnabled(ctx: Context): Boolean {
        val r = ctx.contentResolver
        if (Settings.Secure.getInt(r, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
        val flat = Settings.Secure.getString(r, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return flat.split(':').any { part ->
            val name = part.trim()
            name.startsWith("${ctx.packageName}/") && name.endsWith("SkipAdService")
        }
    }

    fun check(ctx: Context, dnsResult: Pair<Boolean, String>? = null): List<HealthItem> {
        val prefs = AppPrefs(ctx)
        val out = ArrayList<HealthItem>(8)

        // 1. VPN 是否授权
        val granted = runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false)
        out.add(
            HealthItem(
                "vpn_granted", "VPN 授权",
                if (granted) HealthState.OK else HealthState.ERROR,
                if (granted) "已授权，可在需要时启动本地过滤" else "未授权，本地 DNS 过滤无法启动",
                "引导重新授权", HealthAction.VPN_PERMISSION,
            ),
        )

        // 2. DNS 服务是否运行
        val running = VpnState.running.value
        out.add(
            HealthItem(
                "dns_running", "DNS 过滤服务",
                if (running) HealthState.OK else if (granted) HealthState.ERROR else HealthState.WARN,
                if (running) "前台服务运行中" else "未运行",
                "尝试恢复服务", HealthAction.START_VPN,
            ),
        )

        // 3. 无障碍是否开启
        val a11y = accessibilityEnabled(ctx)
        val skipOn = prefs.skipAdEnabled
        out.add(
            HealthItem(
                "a11y_enabled", "无障碍授权",
                when {
                    !skipOn -> HealthState.OK
                    a11y -> HealthState.OK
                    else -> HealthState.ERROR
                },
                when {
                    !skipOn -> "跳过广告未启用，无需授权"
                    a11y -> "系统设置中已开启"
                    else -> "跳过广告已开启，但系统未授权"
                },
                "引导系统设置", HealthAction.ACCESSIBILITY,
            ),
        )

        // 4. 无障碍服务是否真的连上（省电策略会造成「假绑定」）
        val alive = SkipAdState.connected
        out.add(
            HealthItem(
                "a11y_alive", "无障碍服务",
                when {
                    !skipOn -> HealthState.OK
                    !a11y -> HealthState.WARN
                    alive -> HealthState.OK
                    else -> HealthState.ERROR
                },
                when {
                    !skipOn -> "跳过广告未启用"
                    !a11y -> "等待授权"
                    alive -> "服务已连上，最近 60 秒内收到过界面事件"
                    else -> "看似已绑定但服务没在跑，多为省电策略冻结"
                },
                "提示重新启用", HealthAction.ACCESSIBILITY,
            ),
        )

        // 5. 电池优化
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoring = pm?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        out.add(
            HealthItem(
                "battery", "电池优化",
                if (ignoring) HealthState.OK else HealthState.WARN,
                if (ignoring) "已加入白名单，服务不易被冻结" else "未加入白名单，后台服务可能被系统冻结",
                "提供关闭入口", HealthAction.BATTERY,
            ),
        )

        // 6. 规则是否成功加载
        val tier = RuleTier.of(prefs.ruleTier)
        val loaded = RuleStore.isLoaded(tier.key)
        val loadErr = prefs.ruleLoadError
        out.add(
            HealthItem(
                "rules", "过滤规则加载",
                when {
                    loadErr.isNotBlank() -> HealthState.ERROR
                    loaded -> HealthState.OK
                    else -> HealthState.WARN
                },
                when {
                    loadErr.isNotBlank() -> "加载错误：$loadErr"
                    loaded -> "${tier.label} ${RuleStore.blockedOf(tier.key).size} 条已加载"
                    else -> "正在后台加载或尚未启动过服务"
                },
                "显示加载错误 / 重新加载", HealthAction.RELOAD_RULES,
            ),
        )

        // 7. 订阅是否更新成功
        val subErr = prefs.subError
        val never = prefs.subUpdatedAt == 0L && prefs.skipSubAt == 0L
        out.add(
            HealthItem(
                "subs", "订阅更新",
                when {
                    subErr.isNotBlank() -> HealthState.ERROR
                    never -> HealthState.WARN
                    else -> HealthState.OK
                },
                when {
                    subErr.isNotBlank() -> "失败原因：$subErr"
                    never -> "还没有成功更新过（内置规则已随包提供，不影响使用）"
                    else -> "最近一次成功：${formatAt(maxOf(prefs.subUpdatedAt, prefs.skipSubAt))}"
                },
                "显示失败原因 / 重新刷新", HealthAction.REFRESH_SUBS,
            ),
        )

        // 8. 网络 / DNS 连通性
        out.add(
            HealthItem(
                "network", "网络与 DNS 连通性",
                when {
                    dnsResult == null -> HealthState.WARN
                    dnsResult.first -> HealthState.OK
                    else -> HealthState.ERROR
                },
                dnsResult?.second ?: "尚未测试，点右侧「测试」做一次本机解析",
                "提供 DNS 连通性测试", HealthAction.DNS_TEST,
            ),
        )
        return out
    }

    /** 只做一次本机 DNS 解析，不发任何查询内容给第三方，结果不落盘。 */
    suspend fun dnsTest(): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val ex = Executors.newSingleThreadExecutor()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val future = ex.submit(Callable {
                InetAddress.getByName("www.baidu.com").hostAddress
            })
            val ip = future.get(3, TimeUnit.SECONDS)
            val ms = android.os.SystemClock.elapsedRealtime() - started
            true to "解析成功 ${ms}ms（系统解析器返回 $ip）"
        } catch (e: Exception) {
            false to "解析失败：${e.javaClass.simpleName} ${e.message.orEmpty()}"
        } finally {
            ex.shutdownNow()
        }
    }

    private fun formatAt(at: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(at))
}

/**
 * 恢复预算：30 分钟窗口、最多 3 次、间隔 >= 60 秒。
 * 所有自动恢复都必须先过这里，包括开机恢复。
 */
object RecoveryBudget {
    private const val SP = "chengliu_recovery"
    private const val WINDOW_MS = 30 * 60 * 1000L
    private const val MAX_ATTEMPTS = 3
    private const val GAP_MS = 60 * 1000L

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    @Synchronized
    fun allow(ctx: Context): Boolean {
        val p = sp(ctx)
        val now = System.currentTimeMillis()
        var start = p.getLong("window", 0L)
        var count = p.getInt("count", 0)
        val last = p.getLong("last", 0L)
        if (now - start > WINDOW_MS || start == 0L) {
            start = now
            count = 0
        }
        if (count >= MAX_ATTEMPTS) return false
        if (now - last < GAP_MS) return false
        p.edit().putLong("window", start).putInt("count", count + 1)
            .putLong("last", now).apply()
        return true
    }

    fun describe(ctx: Context): String {
        val p = sp(ctx)
        val start = p.getLong("window", 0L)
        val count = p.getInt("count", 0)
        val remain = (MAX_ATTEMPTS - count).coerceAtLeast(0)
        val reset = if (start == 0L) "未开始" else
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                .format(java.util.Date(start + WINDOW_MS))
        return "本窗口已尝试 $count/$MAX_ATTEMPTS 次，剩余 $remain 次，$reset 重置；两次间隔至少 60 秒"
    }

    fun reset(ctx: Context) {
        sp(ctx).edit().clear().apply()
    }
}

object ServiceRecovery {
    /**
     * 只重启自己的 DNS 前台服务。
     * 未授权时**不**启动系统授权界面 —— 那会反复弹窗，比服务没起来更烦人。
     */
    @Synchronized
    fun tryRestartVpn(ctx: Context, why: String): Boolean {
        if (VpnState.running.value) return false
        if (runCatching { VpnService.prepare(ctx) == null }.getOrDefault(false).not()) {
            HealthNotifier.notifyOnce(
                ctx, "vpn_no_permission",
                "本地过滤未运行", "VPN 授权已失效，点开应用在「运行状态自检」里重新授权。",
            )
            return false
        }
        if (!RecoveryBudget.allow(ctx)) {
            android.util.Log.i("Health", "恢复被预算拦下：$why（${RecoveryBudget.describe(ctx)}）")
            return false
        }
        return runCatching {
            LocalVpnService.send(ctx, LocalVpnService.ACTION_START)
            android.util.Log.i("Health", "已尝试恢复 DNS 服务：$why")
            true
        }.getOrDefault(false)
    }

    /** 无障碍服务被系统冻结：只提醒，不尝试后台启用，也不反复弹通知。 */
    fun warnAccessibility(ctx: Context) {
        val prefs = AppPrefs(ctx)
        if (!prefs.skipAdEnabled || !prefs.healthNotify) return
        if (!HealthChecker.accessibilityEnabled(ctx)) return
        HealthNotifier.notifyOnce(
            ctx, "a11y_lost",
            "跳过广告服务已停止",
            "系统可能冻结了无障碍服务。请到系统设置里关闭后重新开启一次 Time Leak。",
        )
    }
}

object HealthNotifier {
    private const val CHANNEL = "chengliu_health"
    private const val SP = "chengliu_health_dedup"
    private const val DEDUP_MS = 30 * 60 * 1000L

    fun notifyOnce(ctx: Context, key: String, title: String, text: String) {
        if (!AppPrefs(ctx).healthNotify) return
        val p = ctx.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - p.getLong(key, 0L) < DEDUP_MS) return
        p.edit().putLong(key, now).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "运行异常提醒", NotificationManager.IMPORTANCE_DEFAULT)
                    .apply { description = "服务异常时最多提示一次，不会反复打扰" },
            )
            val pi = android.app.PendingIntent.getActivity(
                ctx, 0, Intent(ctx, com.chengliu.app.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(com.chengliu.app.R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(key.hashCode(), n)
        }
    }
}
