package com.chengliu.app.core

import java.net.InetAddress
import java.util.Locale

/**
 * 防泄露用的静态表，全部是公开 DNS 服务地址，不含任何用户数据。
 *
 * 取舍说明：VpnService 的路由只能按网段加，不能按端口加。所以「拦住发往公共 DNS
 * 的明文 53」必然同时把这些地址的 443/853 也吸进隧道。加密 DNS 本身不算明文泄露，
 * 但会绕过本地过滤，因此这里一律丢弃 —— 本应用自己的 DoH 走 VpnService.protect
 * 绕开隧道，不受影响。
 *
 * IPv4 与 IPv6 各一份：只拦 v4 等于留了一整条 v6 旁路，而很多应用会优先用 v6 解析。
 */
object Privacy {

    /** 应用可能硬编码的公共 DNS；纳入隧道后，绕过系统 DNS 的查询也会被接管。 */
    val BYPASS_DNS: List<String> = listOf(
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
        "9.9.9.9", "149.112.112.112",
        "208.67.222.222", "208.67.220.220",
        "94.140.14.14", "94.140.15.15",
        "114.114.114.114", "114.114.115.115",
        "223.5.5.5", "223.6.6.6",
        "119.29.29.29", "182.254.116.116",
        "180.76.76.76",
        "1.2.4.8", "210.2.4.8",
        "101.226.4.6", "218.30.118.6",
        "117.50.10.10", "52.80.66.66",
        "185.222.222.222", "45.11.45.11",
        "194.242.2.2",
    )

    /** 同上，IPv6。国内可用的 v6 递归基本只有阿里/腾讯两家，其余是国际公共解析。 */
    val BYPASS_DNS6: List<String> = listOf(
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2620:fe::fe", "2620:fe::9",
        "2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff",
        "2620:119:35::35", "2620:119:53::53",
        "2400:3200::1", "2400:3200:baba::1",
        "2402:4e00::",
        "2a09::", "2a11::",
    )

    private val KEYS: Set<Int> = BYPASS_DNS.mapNotNull { keyOf(it) }.toHashSet()
    private val KEYS6: List<ByteArray> = BYPASS_DNS6.mapNotNull { literal6(it) }

    private fun keyOf(ip: String): Int? {
        val parts = ip.split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    private fun literal6(ip: String): ByteArray? = try {
        InetAddress.getByName(ip).address.takeIf { it.size == 16 }
    } catch (_: Exception) {
        null
    }

    /** 用整数比较，避免每次判包都拼字符串。 */
    fun isBypassDns(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val k = ((ip[0].toInt() and 0xFF) shl 24) or
            ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or
            (ip[3].toInt() and 0xFF)
        return KEYS.contains(k)
    }

    fun isBypassDns6(ip: ByteArray): Boolean {
        if (ip.size != 16) return false
        for (k in KEYS6) if (k.contentEquals(ip)) return true
        return false
    }
}

/**
 * 常见 DoH / DoT 端点。
 *
 * 这张表解决两件事：
 * 1. 连自己的加密上游时不需要任何明文解析（零引导泄露）；
 * 2. 把这些地址纳入接管后，应用自带的加密 DNS 不会成为过滤的盲区。
 *
 * 地址全部来自各家公开文档，只收录「可以确定」的；拿不准的宁可让它走明文引导，
 * 也不写一个错的地址上去 —— 指错 IP 会导致 TLS 一定失败，那比有引导更糟。
 */
object DohEndpoints {

    private val V4: Map<String, List<String>> = mapOf(
        "dns.alidns.com" to listOf("223.5.5.5", "223.6.6.6"),
        "doh.pub" to listOf("1.12.12.12", "120.53.53.53"),
        "dot.pub" to listOf("1.12.12.12", "120.53.53.53"),
        "dns.google" to listOf("8.8.8.8", "8.8.4.4"),
        "cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
        "one.one.one.one" to listOf("1.1.1.1", "1.0.0.1"),
        "mozilla.cloudflare-dns.com" to listOf("1.1.1.1", "1.0.0.1"),
        "dns.quad9.net" to listOf("9.9.9.9", "149.112.112.112"),
        "dns.adguard-dns.com" to listOf("94.140.14.14", "94.140.15.15"),
        "dns.adguard.com" to listOf("94.140.14.14", "94.140.15.15"),
        "doh.opendns.com" to listOf("208.67.222.222", "208.67.220.220"),
        "dns.mullvad.net" to listOf("194.242.2.2"),
        "dns.sb" to listOf("185.222.222.222", "45.11.45.11"),
    )

    private val V6: Map<String, List<String>> = mapOf(
        "dns.alidns.com" to listOf("2400:3200::1", "2400:3200:baba::1"),
        "doh.pub" to listOf("2402:4e00::"),
        "dot.pub" to listOf("2402:4e00::"),
        "dns.google" to listOf("2001:4860:4860::8888", "2001:4860:4860::8844"),
        "cloudflare-dns.com" to listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
        "one.one.one.one" to listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
        "mozilla.cloudflare-dns.com" to listOf("2606:4700:4700::1111", "2606:4700:4700::1001"),
        "dns.quad9.net" to listOf("2620:fe::fe", "2620:fe::9"),
        "dns.adguard-dns.com" to listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
        "dns.adguard.com" to listOf("2a10:50c0::ad1:ff", "2a10:50c0::ad2:ff"),
        "doh.opendns.com" to listOf("2620:119:35::35", "2620:119:53::53"),
        "dns.sb" to listOf("2a09::", "2a11::"),
    )

    /**
     * 服务商独占网段。只收「整段都属于同一个 DNS 服务」的，
     * 绝不碰通用云厂商网段 —— 那种整段接管会把正常网站的 443 一起丢掉。
     */
    val PREFIXES: List<String> = listOf(
        "45.90.28.0/24", "45.90.30.0/24", // NextDNS 的 anycast 段
    )

    private val HINT_SECOND_LEVEL = setOf(
        "nextdns", "controld", "adguard-dns", "quad9", "mullvad", "dnscrypt",
    )

    fun ipsFor(host: String): List<String> = V4[key(host)] ?: emptyList()

    fun ipv6For(host: String): List<String> = V6[key(host)] ?: emptyList()

    /** v4 在前、v6 在后：国内多数网络没有可用的 v6 出口，先试 v4 更稳。 */
    fun addressesFor(host: String): List<String> = ipsFor(host) + ipv6For(host)

    private fun key(host: String) = host.trim().trimEnd('.').lowercase(Locale.ROOT)

    fun isLiteralIp(host: String): Boolean =
        host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }

    fun isIpv6Literal(host: String): Boolean = host.contains(':')

    fun isAnyLiteral(host: String): Boolean = isLiteralIp(host) || isIpv6Literal(host)

    /** 界面上用来告诉用户「这个地址能不能免去明文引导」。 */
    fun isKnown(host: String): Boolean =
        isAnyLiteral(host) || addressesFor(host).isNotEmpty()

    /**
     * 这个名字看起来是不是一个加密 DNS 端点（用来提示「有个应用在找自己的 DoH 服务器」）。
     *
     * 只做命名启发式，不解析任何内容：`dns.` / `doh.` / `dot.` 开头，
     * 或者二级域名属于已知服务商。命中不等于一定有问题，所以只用于展示与手动接管。
     */
    fun looksLikeEndpoint(name: String): Boolean {
        val n = key(name)
        if (n.isEmpty() || n.length > 253) return false
        if (addressesFor(n).isNotEmpty()) return true
        val labels = n.split('.')
        if (labels.size < 2) return false
        val first = labels[0]
        if (first == "dns" || first == "doh" || first == "dot" || first == "dnscrypt") return true
        if (first.startsWith("dns-") || first.startsWith("doh-") || first.startsWith("dot-")) return true
        return labels[labels.size - 2] in HINT_SECOND_LEVEL
    }

    /** 去掉 names 里已经在内置表里的条目，避免提示里出现「已覆盖却还叫你接管」。 */
    fun isBuiltin(name: String): Boolean = addressesFor(key(name)).isNotEmpty()
}
