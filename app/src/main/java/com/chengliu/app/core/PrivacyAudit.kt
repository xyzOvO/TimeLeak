package com.chengliu.app.core

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * 隐私审计：把「存了什么、连了哪里、要了什么权限」摊开给用户看。
 * 这一页只读本机状态，不产生任何网络请求。
 */
object PrivacyAudit {

    data class Entry(val title: String, val value: String, val note: String = "")

    /** 所有本地存储项。写清楚哪些会落盘、哪些只在内存。 */
    fun storage(ctx: Context): List<Entry> {
        val p = AppPrefs(ctx)
        val s = RunStats.state.value
        return listOf(
            Entry("配置与名单", "chengliu_prefs", "开关、规则档位、自定义名单、应用策略、订阅地址"),
            Entry("今日计数", "chengliu_counts", "仅三个整数：查询 / 拦截 / 绕过次数"),
            Entry("运行统计", "chengliu_run_stats", "放行域名去重集合、命中来源分布、最近一次跳过摘要"),
            Entry("恢复预算", "chengliu_recovery", "自动恢复次数与窗口，用于防止无限重启"),
            Entry("提醒去重", "chengliu_health_dedup", "最近一次异常提醒时间"),
            Entry("订阅缓存", "按来源分开保存", "已确认的订阅正文与指纹；可用「一键清理」清空"),
            Entry("拦截明细", "仅内存", "${if (p.blockLog) "开启" else "关闭"}，进程结束即消失，从不落盘"),
            Entry("观察记录", "仅内存", "最多 ${ObservationLog.items.value.size} 条，含界面文字，退出即清空、不进备份"),
            Entry("今日统计", "查询 ${s.queries} / 拦截 ${s.blocked}", "跨天自动归零"),
        )
    }

    /** 所有会联网的端点。说明清楚哪些是用户可改的、哪些是发版固定源。 */
    fun endpoints(ctx: Context): List<Entry> {
        val p = AppPrefs(ctx)
        val list = ArrayList<Entry>()
        p.subscriptions.forEach { list.add(Entry("DNS 规则订阅", it, "只有手动刷新或按天自动更新时才访问")) }
        p.skipSubs.forEach { list.add(Entry("跳过规则订阅", it, "更新前会做差异预览与异常检查")) }
        list.add(Entry("加密 DNS 上游", p.dohUrl, if (p.leakGuard) "防泄露已锁定为 DoH" else "当前可能走系统解析器"))
        list.add(Entry("应用内更新", "github.com / raw / jsDelivr", "仅手动点「检查更新」时访问"))
        list.add(Entry("域名说明库", "随 APK 内置", "运行时不联网查询任何域名归属"))
        return list
    }

    fun permissions(ctx: Context): List<Entry> {
        fun state(perm: String): String = runCatching {
            if (ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) "已授予" else "未授予"
        }.getOrDefault("不适用")
        // Android 13 之前不存在 POST_NOTIFICATIONS，通知随系统自动可用。直接
        // checkSelfPermission 只会拿到「未授予」，让用户误以为前台服务被禁了。
        // 用字符串字面量而不是 Manifest.permission 常量，顺带避开 API 33 字段内联告警。
        val notify = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            state("android.permission.POST_NOTIFICATIONS")
        } else "该版本无需申请，随系统自动授予"
        return listOf(
            Entry("VPN", "android.permission.BIND_VPN_SERVICE", "用于只接管 DNS，不转发应用流量"),
            Entry("无障碍", "android.permission.BIND_ACCESSIBILITY_SERVICE", "仅用于识别跳过按钮；可完全关闭"),
            Entry("网络", state(android.Manifest.permission.INTERNET), "刷新订阅 / 检查更新 / 加密 DNS"),
            Entry("通知", notify, "前台服务与异常提醒"),
            Entry("开机启动", "RECEIVE_BOOT_COMPLETED", "仅在用户打开「开机恢复」时使用"),
            Entry("安装应用", "REQUEST_INSTALL_PACKAGES", "仅应用内更新安装 APK 时使用"),
            Entry("位置 / 通讯录 / 存储", "未申请", "一项都没有"),
        )
    }

    /** DNS 加密状态：把「看起来是加密」和「实际强制加密」分开说清楚。 */
    fun dnsStatus(ctx: Context): Entry {
        val p = AppPrefs(ctx)
        return when {
            p.leakGuard -> Entry(
                "DNS 加密状态", "强制加密（DoH）",
                "防泄露已开启：忽略系统上游，一律走 ${p.dohUrl}，连不上就失败、不回退明文",
            )
            p.upstreamMode == "doh" -> Entry("DNS 加密状态", "加密（DoH）", p.dohUrl)
            else -> Entry(
                "DNS 加密状态", "未强制加密",
                "当前按${if (p.upstreamMode == "ip") "指定 IP" else "系统解析器"}转发；可在「防泄露」里锁定 DoH",
            )
        }
    }

    /** VPN 流量范围：DNS-only 架构必须说清，免得用户以为所有流量都过我们的服务。 */
    fun vpnScope(ctx: Context): Entry = Entry(
        "VPN 流量范围", "仅 DNS",
        "虚拟 DNS ${LocalVpnService.VIRTUAL_DNS} 与公共 DNS 的 /32 路由进隧道，没有默认路由。" +
            "应用自身的 HTTP、视频、下载等流量完全不经过本服务；因此也无法做「哪个 App 发的查询」归因。",
    )

    /** 只清运行数据：统计、拦截明细、观察记录。配置 / 名单 / 策略不动。 */
    fun clearRuntime(ctx: Context) {
        RunStats.clear()
        Counters.clear()
        BlockLog.clear()
        ObservationLog.clear()
        RecoveryBudget.reset(ctx)
    }

    /** 清全部本地数据：连配置、订阅缓存、白名单、备份版本一起清。 */
    fun clearAll(ctx: Context) {
        clearRuntime(ctx)
        listOf(
            "chengliu_prefs", "chengliu_counts", "chengliu_run_stats",
            "chengliu_recovery", "chengliu_health_dedup",
        ).forEach { name ->
            runCatching {
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().apply()
            }
        }
        RunStats.init(ctx)
        Counters.init(ctx)
    }
}
