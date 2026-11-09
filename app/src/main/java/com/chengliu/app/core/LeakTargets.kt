package com.chengliu.app.core

import com.chengliu.app.BuildConfig
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.Locale

/**
 * 用户自定义的「必须接管」目标。
 *
 * 只接受 IPv4 单地址与 /24 及更窄的网段。原因很实际：VpnService 的路由只能按网段加，
 * 把 /8 或者一大批普通域名解析出的 IP 塞进来，会顺带把正常应用的 443 吞掉，
 * 表现就是「加了规则就断网」。DNS 服务端点本身就是单个地址，没必要放开这个口子。
 *
 * 域名只解析「用户手写的那几个」，不解析订阅列表里的域名 —— 公开分流列表动辄几千个域名，
 * 全部解析会把隧道变成半个全局 VPN。
 */
object LeakTargets {

    const val MAX_TARGETS = 200
    private const val MAX_BODY = 512 * 1024
    private const val MAX_SUBS = 8

    data class Parsed(
        val ips: List<String>,
        val domains: List<String>,
        val urls: List<String>,
        val skipped: Int,
        val skippedSample: List<String>,
    )

    fun parse(text: String): Parsed {
        val ips = LinkedHashSet<String>()
        val domains = LinkedHashSet<String>()
        val urls = LinkedHashSet<String>()
        var skipped = 0
        val sample = ArrayList<String>(4)

        fun drop(line: String) {
            skipped++
            if (sample.size < 4) sample.add(line)
        }

        for (raw in text.lineSequence()) {
            var s = raw.trim()
            if (s.isEmpty()) continue
            if (s.startsWith("#") || s.startsWith(";") || s.startsWith("//") || s.startsWith("[")) continue

            // Subconverter 的 ruleset=策略,[可选标签],条目 写法。
            // 只有条目本身是 URL 时才拉取；DOMAIN-SUFFIX / GEOIP 这类分流规则直接丢弃，
            // 它们描述的是「流量往哪走」，不是「哪个地址提供 DNS」。
            if (s.startsWith("ruleset=", ignoreCase = true)) {
                val body = s.substringAfter('=').trim()
                val at = body.indexOf("http")
                if (at < 0) {
                    drop(raw.trim())
                    continue
                }
                s = body.substring(at).trim().substringBefore(' ')
            } else if (s.startsWith("IP-CIDR,", ignoreCase = true) || s.startsWith("IP-CIDR6,", ignoreCase = true)) {
                val first = s.indexOf(',')
                val next = s.indexOf(',', first + 1)
                s = if (next > 0) s.substring(first + 1, next).trim() else s.substring(first + 1).trim()
            }

            if (s.isEmpty()) {
                drop(raw.trim())
                continue
            }
            if (s.contains("://")) {
                if (urls.size < MAX_SUBS && s.length <= 300) urls.add(s) else drop(raw.trim())
                continue
            }
            if (s.startsWith("DOMAIN", ignoreCase = true) ||
                s.startsWith("GEOIP", ignoreCase = true) ||
                s.startsWith("FINAL", ignoreCase = true) ||
                s.startsWith("MATCH", ignoreCase = true)
            ) {
                drop(raw.trim())
                continue
            }

            val route = normalizeRoute(s)
            if (route != null) {
                if (ips.size < MAX_TARGETS) ips.add(route) else drop(raw.trim())
                continue
            }
            // IPv6 单地址或 /64~/128 网段
            val route6 = normalizeRoute6(s)
            if (route6 != null) {
                if (ips.size < MAX_TARGETS) ips.add(route6) else drop(raw.trim())
                continue
            }
            val domain = normalizeDomain(s)
            if (domain != null && !DohEndpoints.isAnyLiteral(domain)) {
                if (domains.size < 32) domains.add(domain) else drop(raw.trim())
            } else {
                drop(raw.trim())
            }
        }
        return Parsed(ips.toList(), domains.toList(), urls.toList(), skipped, sample)
    }

    /** 接受 1.1.1.1 或 1.1.1.0/24..32；私有段、回环、链路本地一律拒绝。 */
    fun normalizeRoute(raw: String): String? {
        var s = raw.trim()
        var prefix = 32
        val slash = s.indexOf('/')
        if (slash >= 0) {
            prefix = s.substring(slash + 1).trim().toIntOrNull() ?: return null
            s = s.substring(0, slash).trim()
            if (prefix !in 24..32) return null
        }
        val parts = s.split('.')
        if (parts.size != 4) return null
        val n = IntArray(4)
        for (i in 0..3) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            n[i] = v
        }
        if (n[0] == 10 || n[0] == 0 || n[0] == 127) return null
        if (n[0] == 169 && n[1] == 254) return null
        if (n[0] == 192 && n[1] == 168) return null
        if (n[0] == 172 && n[1] in 16..31) return null
        if (n[0] == 100 && n[1] in 64..127) return null
        val ip = "${n[0]}.${n[1]}.${n[2]}.${n[3]}"
        return if (prefix == 32) ip else "$ip/$prefix"
    }

    /** IPv6 单地址（补 /128）或 /64~/128；私有段、回环、链路本地、组播一律拒绝。 */
    fun normalizeRoute6(raw: String): String? {
        val cidr = CaptureRoutes.normalize(raw) ?: return null
        return cidr.takeIf { it.contains(':') }
    }

    data class Result(
        val ips: List<String>,
        val ok: Int,
        val total: Int,
        val skipped: Int,
        val note: String,
        val failed: Boolean,
    )

    /**
     * 合并「手写条目 + 订阅内容」。订阅里的域名不解析，只取 IP/CIDR。
     * 网络请求都在调用方的 IO 线程里跑，这里不切线程。
     */
    fun refresh(text: String): Result {
        val head = parse(text)
        val all = LinkedHashSet(head.ips)
        var skipped = head.skipped
        var ok = 0
        var failedSources = 0

        // 手写域名解析成地址（v4 与 v6 都要）；失败只计数，不影响其它条目
        for (d in head.domains) {
            val addrs = try {
                InetAddress.getAllByName(d)
            } catch (_: Exception) {
                failedSources++
                null
            }
            if (addrs == null) {
                skipped++
                continue
            }
            var got = false
            for (a in addrs) {
                val ip = usableAddress(a) ?: continue
                if (all.size < MAX_TARGETS) {
                    all.add(ip)
                    got = true
                }
            }
            if (!got) skipped++
        }

        for (u in head.urls) {
            val body = fetch(u)
            if (body == null) {
                failedSources++
                continue
            }
            val sub = parse(body)
            ok++
            // 订阅里的 URL 不再递归，域名也不解析，只统计忽略数
            skipped += sub.skipped + sub.urls.size + sub.domains.size
            for (ip in sub.ips) {
                if (all.size >= MAX_TARGETS) {
                    skipped++
                    continue
                }
                all.add(ip)
            }
        }

        val note = buildString {
            append("可用目标 ${all.size} 个")
            if (head.urls.isNotEmpty()) {
                if (ok == 0) append("；${head.urls.size} 个订阅全部失败")
                else append("；订阅成功 $ok/${head.urls.size}")
            }
            if (skipped > 0) append("；忽略 $skipped 条")
        }
        return Result(all.toList(), ok, head.urls.size, skipped, note, head.urls.isNotEmpty() && ok == 0)
    }

    /**
     * 把几个域名解析成地址并并入接管列表（界面上「一键接管」用）。
     *
     * 解析发生在应用进程里，走的是系统解析器 —— 也就是本应用自己的隧道，
     * 所以这一步不会产生额外的明文查询。
     */
    fun captureHosts(current: Collection<String>, hosts: Collection<String>): List<String> {
        val out = LinkedHashSet<String>(current)
        for (h in hosts) {
            if (out.size >= MAX_TARGETS) break
            val addrs = try {
                InetAddress.getAllByName(h)
            } catch (_: Exception) {
                null
            }
            for (a in addrs ?: continue) {
                val ip = usableAddress(a) ?: continue
                if (out.size < MAX_TARGETS) out.add(ip)
            }
        }
        return out.toList()
    }

    /** 过滤掉回环/链路本地，并去掉 IPv6 的 scope 后缀（%wlan0 这种没法写进路由）。 */
    private fun usableAddress(a: InetAddress): String? {
        if (a.isLoopbackAddress || a.isLinkLocalAddress || a.isAnyLocalAddress) return null
        val raw = a.hostAddress ?: return null
        val pct = raw.indexOf('%')
        return if (pct > 0) raw.substring(0, pct) else raw
    }

    private fun fetch(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "TimeLeak/${BuildConfig.VERSION_NAME}")
            if (conn.responseCode !in 200..299) return null
            val input = conn.inputStream
            val out = ByteArrayOutputStream(8192)
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_BODY) break
                out.write(buf, 0, n)
            }
            out.toString("UTF-8")
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** 给界面用的一句话解释，避免把格式说明散在多个地方。 */
    fun formatHint(): String =
        "一行一条，支持 IPv4 / IPv6、/24~/32 与 /64~/128 网段、DNS 服务器域名，以及规则文件地址。" +
            "订阅里的域名不会被解析，只取 IP 与网段。"
}
