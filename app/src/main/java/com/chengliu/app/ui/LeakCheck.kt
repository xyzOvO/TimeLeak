package com.chengliu.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.chengliu.app.core.AppPrefs
import com.chengliu.app.core.CapturePlan
import com.chengliu.app.core.Counters
import com.chengliu.app.core.DohEndpoints
import com.chengliu.app.core.DohResolver
import com.chengliu.app.core.LocalVpnService
import com.chengliu.app.core.PrivateDns
import com.chengliu.app.core.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * 本机自检。只发几条自己构造的探测查询，不采集任何用户域名，
 * 也不把结果写盘 —— 关掉页面就没了。
 *
 * 每一项都用「真发一次包」验证，而不是读配置猜结论：
 * 私人 DNS 这类系统设置无法用代码改，唯一能确认它有没有绕过过滤的办法，就是看
 * 系统解析器发出的查询有没有真的落到本应用的计数里。
 */
object LeakCheck {

    const val PASS = 0
    const val NOTICE = 1
    const val RISK = 2

    data class Result(val title: String, val level: Int, val detail: String)

    suspend fun run(ctx: Context, prefs: AppPrefs): List<Result> = withContext(Dispatchers.IO) {
        val out = ArrayList<Result>(8)
        val running = VpnState.running.value
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val privateDns = PrivateDns.read(ctx)
        val (_, dropped) = CapturePlan.resolve(prefs.leakTargetIps, privateDns)

        // 1. 系统解析器是否真的指向本地隧道（v4 与 v6 分别看）
        val systemDns = try {
            cm?.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }
        } catch (_: Exception) {
            null
        }
        val dnsText = systemDns?.joinToString(" / ")?.ifEmpty { "未知" } ?: "未知"
        val has4 = systemDns?.contains(LocalVpnService.VIRTUAL_DNS) == true
        val has6 = systemDns?.contains(LocalVpnService.VIRTUAL_DNS6) == true
        out.add(
            when {
                !running -> Result("系统 DNS 接管", NOTICE, "过滤未开启，先点首页按钮启动再看这项。")
                has4 && has6 -> Result(
                    "系统 DNS 接管",
                    PASS,
                    "系统解析器指向 ${LocalVpnService.VIRTUAL_DNS} 与 ${LocalVpnService.VIRTUAL_DNS6}，" +
                        "IPv4 / IPv6 查询都会先进隧道。",
                )
                has4 -> Result(
                    "系统 DNS 接管",
                    NOTICE,
                    "已接管 IPv4（${LocalVpnService.VIRTUAL_DNS}），但系统没有采用 IPv6 的虚拟 DNS，" +
                        "当前解析器是 $dnsText。本机可能没有 IPv6 出口。",
                )
                else -> Result(
                    "系统 DNS 接管",
                    RISK,
                    "系统解析器仍是 $dnsText，查询没有经过本应用。",
                )
            },
        )

        // 2. 硬编码公共 DNS 的查询有没有被接住（v4 与 v6 各发一条，用计数验证）
        if (!running) {
            out.add(Result("旁路 53 接管", NOTICE, "过滤未开启，跳过这项探测。"))
        } else if (!prefs.blockBypass) {
            out.add(Result("旁路 53 接管", RISK, "「接管硬编码 DNS」是关闭的，应用可以直接连公共 DNS 明文查询。"))
        } else {
            val id = System.nanoTime().toString(16)
            val before = Counters.bypass.value
            val reply4 = udpQuery("8.8.8.8", buildQuery("v4-$id.chengliu-check.invalid"), 1600)
            val reply6 = udpQuery("2001:4860:4860::8888", buildQuery("v6-$id.chengliu-check.invalid"), 1600)
            delay(240)
            val after = Counters.bypass.value
            out.add(
                when {
                    after > before -> Result(
                        "旁路 53 接管",
                        PASS,
                        "发往 8.8.8.8 与 2001:4860:4860::8888 的明文查询都进了隧道" +
                            "（计数 +${after - before}），不会再直接出网。",
                    )
                    reply4 != null || reply6 != null -> Result(
                        "旁路 53 接管",
                        RISK,
                        "有公共 DNS 仍能直接连上且没进隧道，这是明文泄露通道。试试关闭再开启过滤。",
                    )
                    else -> Result(
                        "旁路 53 接管",
                        NOTICE,
                        "探测没有回应，也没看到接管计数；可能是当前网络本身不通这些地址。",
                    )
                },
            )
        }

        // 3. IPv6 的虚拟 DNS 是否真的可用
        if (!running) {
            out.add(Result("IPv6 DNS 接管", NOTICE, "过滤未开启，跳过这项探测。"))
        } else {
            val reply = udpQuery(
                LocalVpnService.VIRTUAL_DNS6,
                buildQuery("v6dns-${System.nanoTime().toString(16)}.chengliu-check.invalid"),
                2500,
            )
            val uplink = hasIpv6Uplink(cm)
            out.add(
                when {
                    reply != null && reply.size >= 4 ->
                        Result("IPv6 DNS 接管", PASS, "向 ${LocalVpnService.VIRTUAL_DNS6} 发的查询拿到了应答，隧道内的 v6 解析可用。")
                    uplink -> Result(
                        "IPv6 DNS 接管",
                        RISK,
                        "本机有 IPv6 出口，但发往 ${LocalVpnService.VIRTUAL_DNS6} 的探测没有回应，" +
                            "v6 查询可能仍在走物理网络。请关闭再开启一次过滤。",
                    )
                    else -> Result(
                        "IPv6 DNS 接管",
                        NOTICE,
                        "本机当前没有可用的 IPv6 出口，这一项暂时没有实际影响；隧道里的 v6 应答路径已就绪。",
                    )
                },
            )
        }

        // 4. 系统解析器发出的查询到底有没有落到本应用（能抓到私人 DNS 与系统级 DoH 的绕过）
        if (!running) {
            out.add(Result("系统解析回环", NOTICE, "过滤未开启，跳过这项探测。"))
        } else {
            val before = Counters.today.value.first
            try {
                InetAddress.getByName("sys-${System.nanoTime().toString(16)}.chengliu-check.invalid")
            } catch (_: Exception) {
            }
            delay(280)
            val after = Counters.today.value.first
            out.add(
                if (after > before) {
                    Result("系统解析回环", PASS, "系统解析器发出的查询确实进了本应用（计数 +${after - before}）。")
                } else {
                    Result(
                        "系统解析回环",
                        RISK,
                        "系统解析没有经过本应用：解析被别的通道接管了（最常见的是系统「私人 DNS」，" +
                            "其次是系统级加密 DNS）。下面的私人 DNS 一项会给出具体处理方式。",
                    )
                },
            )
        }

        // 5. 系统私人 DNS：只读状态 + 说明「已经为它让路了哪几个地址」
        val note = PrivateDns.exclusionNote(privateDns, dropped)
        out.add(
            when (privateDns.mode) {
                PrivateDns.Mode.OFF -> Result("私人 DNS", PASS, PrivateDns.summary(privateDns))
                PrivateDns.Mode.AUTO -> Result("私人 DNS", NOTICE, PrivateDns.summary(privateDns))
                PrivateDns.Mode.STRICT -> Result(
                    "私人 DNS",
                    RISK,
                    listOfNotNull(PrivateDns.summary(privateDns), note).joinToString(" "),
                )
                PrivateDns.Mode.UNKNOWN -> Result("私人 DNS", NOTICE, PrivateDns.summary(privateDns))
            },
        )

        // 6. 加密上游是否可用，以及是否需要明文引导
        val resolver = DohResolver(prefs.dohUrl)
        val needBootstrap = resolver.needsBootstrap()
        val ip = DohEndpoints.addressesFor(resolver.host).firstOrNull()
        val tlsOk = if (resolver.host.isEmpty()) false else probeTls(cm, resolver.host, ip)
        out.add(
            when {
                resolver.host.isEmpty() -> Result("加密上游", RISK, "DoH 地址无效，防泄露模式下会导致无法解析。")
                tlsOk && !needBootstrap -> Result(
                    "加密上游",
                    PASS,
                    "${resolver.host} 的 TLS 握手正常，且内置了它的地址，转发过程零明文引导。",
                )
                tlsOk -> Result(
                    "加密上游",
                    NOTICE,
                    "${resolver.host} 可连通，但它不在内置地址表里，每次启动需要通过一次明文查询拿到地址。",
                )
                else -> Result(
                    "加密上游",
                    RISK,
                    "连不上 ${resolver.host} 的 443。防泄露模式不会回退明文，此时会表现为解析失败。",
                )
            },
        )

        // 7. 反劫持：保留域名 .invalid 必须返回 NXDOMAIN，返回 A 记录就是被中间人接管
        if (!running) {
            out.add(Result("反劫持", NOTICE, "过滤未开启，跳过这项探测。"))
        } else {
            val name = "nx-${System.nanoTime().toString(16)}.chengliu-check.invalid"
            val reply = udpQuery(LocalVpnService.VIRTUAL_DNS, buildQuery(name), 2500)
            val rcode = reply?.let { if (it.size >= 4) it[3].toInt() and 0x0F else -1 } ?: -1
            val answers = reply?.let { if (it.size >= 7) ((it[6].toInt() and 0xFF) shl 8) or (it[7].toInt() and 0xFF) else 0 } ?: 0
            out.add(
                when {
                    rcode == 3 -> Result("反劫持", PASS, "保留域名返回 NXDOMAIN，上游没有插广告或跳转。")
                    rcode == 0 && answers > 0 -> Result(
                        "反劫持",
                        RISK,
                        "保留域名被解析出了 $answers 条记录，说明链路上有中间人在改写应答。",
                    )
                    reply == null -> Result("反劫持", NOTICE, "探测超时，当前网络或上游没有回应。")
                    else -> Result("反劫持", NOTICE, "应答码 $rcode，不是预期的 NXDOMAIN，仅供参考。")
                },
            )
        }

        out
    }

    /** 本机是否有真正的 IPv6 出口（非 VPN 网络上存在全局 v6 地址）。 */
    private fun hasIpv6Uplink(cm: ConnectivityManager?): Boolean {
        if (cm == null) return false
        return try {
            cm.allNetworks.any { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@any false
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return@any false
                cm.getLinkProperties(net)?.linkAddresses?.any { la ->
                    la.address is Inet6Address && !la.address.isLinkLocalAddress && !la.address.isLoopbackAddress
                } == true
            }
        } catch (_: Exception) {
            false
        }
    }

    /** 只做 TCP + TLS 握手，足够判断「加密上游通不通、证书对不对」。 */
    private fun probeTls(cm: ConnectivityManager?, host: String, ip: String?): Boolean {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        return try {
            raw = Socket()
            // 预绑定只对 IPv4 做，否则会把 socket 钉在 AF_INET 上连不了 v6 地址
            val target = ip ?: host
            if (!target.contains(':')) raw.bind(InetSocketAddress(0))
            // 绑到物理网络，绕开自己的隧道：这些地址在旁路路由里，不绕就必然连不上
            physical(cm)?.bindSocket(raw)
            raw.connect(InetSocketAddress(target, 443), 4000)
            raw.soTimeout = 5000
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tls = factory.createSocket(raw, host, 443, true) as SSLSocket
            tls.soTimeout = 5000
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            true
        } catch (_: Exception) {
            false
        } finally {
            try {
                tls?.close()
            } catch (_: Exception) {
            }
            try {
                raw?.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun physical(cm: ConnectivityManager?): Network? {
        if (cm == null) return null
        return try {
            cm.allNetworks.firstOrNull { n ->
                val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun udpQuery(host: String, query: ByteArray, timeoutMs: Int): ByteArray? {
        var s: DatagramSocket? = null
        return try {
            // 目的地址是 v6 时必须开 v6 socket，否则连发都发不出去
            s = if (host.contains(':')) {
                DatagramSocket(0, InetAddress.getByName("::"))
            } else {
                DatagramSocket()
            }
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(query, query.size, InetAddress.getByName(host), 53))
            val buf = ByteArray(1500)
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            buf.copyOf(p.length)
        } catch (_: Exception) {
            null
        } finally {
            try {
                s?.close()
            } catch (_: Exception) {
            }
        }
    }

    /** 手工拼一个 A 记录查询，不依赖任何第三方库。 */
    private fun buildQuery(name: String): ByteArray {
        val out = ByteArrayOutputStream(64)
        val id = (System.nanoTime() and 0xFFFF).toInt()
        out.write((id shr 8) and 0xFF)
        out.write(id and 0xFF)
        out.write(0x01) // RD
        out.write(0x00)
        out.write(byteArrayOf(0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.lowercase(Locale.ROOT).split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size and 0xFF)
            out.write(b)
        }
        out.write(0)
        out.write(byteArrayOf(0, 1, 0, 1))
        return out.toByteArray()
    }
}
