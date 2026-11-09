package com.chengliu.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.Locale
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

interface UpstreamResolver {
    fun resolve(msg: ByteArray, len: Int): ByteArray?
}

/**
 * 明文 UDP/53 上游。
 *
 * 有两处必须做对，否则会出现「VPN 一起来，全网 DNS 全挂」：
 * 1. 选目标 DNS 时必须排除 VPN 网络。VPN 成为活动网络后，它的 DNS 就是本机虚拟地址，
 *    把查询发给它等于发给自己；而物理网络上根本不存在这个主机，只能等到超时。
 * 2. socket 既要绑定到物理网络，也要 protect，避免在多网络设备（WiFi + 移动数据）上走错出口或绕回 tun。
 */
class SystemUdpResolver(
    vpn: VpnService,
    fixedIp: String = "",
) : UpstreamResolver {

    private val service: VpnService = vpn
    private val bindNetwork: Network?
    private val target: InetAddress

    init {
        val picked = pick(vpn, fixedIp)
        bindNetwork = picked.first
        target = picked.second
    }

    private fun pick(vpn: VpnService, fixed: String): Pair<Network?, InetAddress> {
        if (fixed.isNotBlank()) {
            try {
                // 指定 IPv6 上游也可以：下面按地址族开对应 socket
                return null to InetAddress.getByName(fixed.trim())
            } catch (_: Exception) {
                // 填错了就退回自动探测，不让用户直接断网
            }
        }
        try {
            val cm = vpn.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                // 第一优先：带 INTERNET 且明确不是 VPN 的网络，先找 IPv4
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
                        val v4 = firstUsable(cm.getLinkProperties(net)?.dnsServers, v4 = true)
                        if (v4 != null) return net to v4
                    }
                }
                // 第二优先：不看网络标记，只按地址排除本机虚拟网段与保留地址
                for (net in cm.allNetworks) {
                    val v4 = firstUsable(cm.getLinkProperties(net)?.dnsServers, v4 = true)
                    if (v4 != null) return net to v4
                }
                // 第三优先：只有 IPv6 DNS 的网络（纯 v6 / 464XLAT 环境）
                for (net in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(net) ?: continue
                    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                    val v6 = firstUsable(cm.getLinkProperties(net)?.dnsServers, v4 = false)
                    if (v6 != null) return net to v6
                }
            }
        } catch (_: Exception) {
        }
        // 国内可直连的公共递归，作为最后兜底
        return null to InetAddress.getByName("223.5.5.5")
    }

    /** 只接受公网可路由的地址：排除回环、链路本地、通配，以及本机虚拟 DNS 地址。 */
    private fun firstUsable(list: List<InetAddress>?, v4: Boolean): InetAddress? = list?.firstOrNull {
        val familyOk = if (v4) it is Inet4Address else it is Inet6Address
        familyOk &&
            !it.isLoopbackAddress &&
            !it.isLinkLocalAddress &&
            !it.isAnyLocalAddress &&
            it.hostAddress?.startsWith(VPN_PREFIX) != true
    }

    override fun resolve(msg: ByteArray, len: Int): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            // 目的地址是 IPv6 时必须开 v6 socket：默认的 DatagramSocket(0) 绑在 0.0.0.0 上，
            // 往上送 v6 报文只会得到「Address family not supported」。
            socket = if (target is Inet6Address) {
                DatagramSocket(0, InetAddress.getByName("::"))
            } else {
                DatagramSocket(0)
            }
            // 先绑定物理网络再 protect：前者决定路由表，后者只是让包绕开 VPN 的 fd
            try {
                bindNetwork?.bindSocket(socket)
            } catch (_: Exception) {
            }
            if (!service.protect(socket)) return null
            socket.soTimeout = 2500
            socket.send(DatagramPacket(msg.copyOf(len), len, target, 53))
            val buf = ByteArray(4096)
            val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            buf.copyOf(reply.length)
        } catch (_: Exception) {
            null
        } finally {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        private const val VPN_PREFIX = "10.225.0."
    }
}

/**
 * 加密上游。两条路径，优先走第一条：
 *
 * 1. 内置表命中 → 直接用 IP 建 TLS 连接，SNI 与证书校验仍按域名做。
 *    这条路径完全不经过系统解析器，所以不存在「为了连加密 DNS 先发一个明文查询」的泄露。
 * 2. 表外域名 → 退回 HttpURLConnection。它的主机名解析由服务特判走明文通道，
 *    界面上会把这种情况标成「需要明文引导」，不假装它没有代价。
 *
 * 这些 DoH 服务地址同时也在旁路接管列表里（要拦它们的 53），
 * 所以自建 socket 必须先 bind 拿到 fd、再 protect、最后 connect；
 * 顺序反了 SYN 会先被隧道吞掉，导致加密上游整体连不上。
 */
class DohResolver(
    url: String,
    private val protectSocket: ((Socket) -> Boolean)? = null,
) : UpstreamResolver {

    private data class Target(val host: String, val port: Int, val path: String)

    private val target: Target = parseTarget(url)

    val host: String get() = target.host

    /** true = 内置表里没有这个地址，转发时得先做一次明文解析。 */
    fun needsBootstrap(): Boolean =
        target.host.isNotEmpty() &&
            !DohEndpoints.isAnyLiteral(target.host) &&
            DohEndpoints.addressesFor(target.host).isEmpty()

    override fun resolve(msg: ByteArray, len: Int): ByteArray? {
        if (target.host.isEmpty() || target.port <= 0) return null
        val ips = if (DohEndpoints.isAnyLiteral(target.host)) {
            listOf(target.host)
        } else {
            DohEndpoints.addressesFor(target.host)
        }
        if (ips.isEmpty()) return postViaHttp(msg, len)
        for (ip in ips) {
            val r = postViaTls(ip, msg, len)
            if (r != null && r.isNotEmpty()) return r
        }
        // 表里有 IP 但都连不上时宁可失败，也不回退明文 —— 回退就等于泄露。
        return null
    }

    private fun postViaTls(ip: String, msg: ByteArray, len: Int): ByteArray? {
        var raw: Socket? = null
        var tls: SSLSocket? = null
        return try {
            raw = Socket()
            // 预绑定只对 IPv4 做：bind(0) 拿到的是 AF_INET 的 socket，
            // 绑上之后再连 IPv6 地址会直接报「Address family not supported」。
            if (!ip.contains(':')) {
                try {
                    raw.bind(InetSocketAddress(0))
                } catch (_: Exception) {
                }
            }
            try {
                protectSocket?.invoke(raw)
            } catch (_: Exception) {
            }
            raw.connect(InetSocketAddress(ip, target.port), CONNECT_TIMEOUT)
            raw.soTimeout = READ_TIMEOUT

            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            // 传 host 而不是 ip：SNI 与证书主机名校验都按域名走，TCP 却连在指定 IP 上
            tls = factory.createSocket(raw, target.host, target.port, true) as SSLSocket
            tls.soTimeout = READ_TIMEOUT
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()

            val head = buildString {
                append("POST ").append(target.path).append(" HTTP/1.1\r\n")
                append("Host: ")
                if (target.port == 443) append(target.host) else append(target.host).append(':').append(target.port)
                append("\r\nContent-Type: ").append(MEDIA_NAME)
                append("\r\nAccept: ").append(MEDIA_NAME)
                append("\r\nContent-Length: ").append(len)
                append("\r\nConnection: close\r\n\r\n")
            }
            val out = tls.outputStream
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(msg, 0, len)
            out.flush()
            readDnsBody(tls.inputStream)
        } catch (_: Exception) {
            null
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

    /** 表外域名走这里；连接与解析都由系统负责，可能需要一次明文引导。 */
    private fun postViaHttp(msg: ByteArray, len: Int): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(buildString {
                append("https://").append(target.host)
                if (target.port != 443) append(':').append(target.port)
                append(target.path)
            }).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", MEDIA_NAME)
            conn.setRequestProperty("Accept", MEDIA_NAME)
            conn.setFixedLengthStreamingMode(len)
            conn.outputStream.use { it.write(msg, 0, len) }
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readDnsBody(input: InputStream): ByteArray? {
        val head = ByteArrayOutputStream(512)
        var state = 0
        while (head.size() < 8192) {
            val b = input.read()
            if (b < 0) return null
            head.write(b)
            state = when {
                state == 0 && b == 13 -> 1
                state == 1 && b == 10 -> 2
                state == 2 && b == 13 -> 3
                state == 3 && b == 10 -> return bodyOf(input, head.toString("ISO-8859-1"))
                else -> 0
            }
        }
        return null
    }

    private fun bodyOf(input: InputStream, head: String): ByteArray? {
        val lines = head.split("\r\n")
        val status = lines.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
        if (status !in 200..299) return null
        var chunked = false
        var length = -1
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (name == "content-length") length = value.toIntOrNull() ?: -1
            if (name == "transfer-encoding" && value.contains("chunked", ignoreCase = true)) chunked = true
        }
        if (chunked) return readChunked(input)
        if (length <= 0) return null
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        return if (read == length) buf else null
    }

    private fun readChunked(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        while (true) {
            val line = readLine(input) ?: return null
            val size = line.substringBefore(';').trim().toIntOrNull(16) ?: return null
            if (size == 0) return out.toByteArray()
            if (out.size() + size > 65535) return null
            val buf = ByteArray(size)
            var read = 0
            while (read < size) {
                val n = input.read(buf, read, size - read)
                if (n < 0) return null
                read += n
            }
            out.write(buf)
            readLine(input)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder(32)
        while (sb.length < 1024) {
            val b = input.read()
            if (b < 0) return null
            if (b == 10) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
        return null
    }

    private fun parseTarget(raw: String): Target = try {
        val u = URL(raw.trim())
        if (u.protocol?.lowercase(Locale.ROOT) != "https") {
            Target("", 0, "/")
        } else {
            Target(
                u.host ?: "",
                if (u.port > 0) u.port else 443,
                (u.file ?: "/").ifEmpty { "/" },
            )
        }
    } catch (_: Exception) {
        Target("", 0, "/")
    }

    companion object {
        private const val MEDIA_NAME = "application/dns-message"
        private const val CONNECT_TIMEOUT = 4000
        private const val READ_TIMEOUT = 6000
    }
}
