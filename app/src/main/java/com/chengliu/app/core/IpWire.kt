package com.chengliu.app.core

/**
 * 极简 IPv4 / IPv6 + UDP 封装。
 *
 * IPv4 的 UDP 校验和按规范可以置 0，只算 20 字节头校验和；
 * IPv6 没有这层豁免，UDP 校验和必须正确计算（含伪首部），否则内核会静默丢包，
 * 表现就是「隧道里有应答、应用却一直超时」。所以两套包头各写一份，互不复用。
 */
object IpWire {
    private const val PROTO_UDP = 17
    private const val PROTO_ICMPV6 = 58

    data class Datagram(
        val src: ByteArray,
        val dst: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    data class Datagram6(
        val src: ByteArray,
        val dst: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    fun parseIpv4Udp(pkt: ByteArray, length: Int): Datagram? {
        if (length < 28) return null
        if ((pkt[0].toInt() and 0xFF) shr 4 != 4) return null
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl + 8) return null
        if (pkt[9].toInt() and 0xFF != PROTO_UDP) return null
        val total = u16(pkt, 2)
        if (total < ihl + 8 || total > length) return null
        // 分片包不解析；DNS 查询不会分片
        if (u16(pkt, 6) and 0x1FFF != 0) return null

        val u = ihl
        val udpLen = u16(pkt, u + 4)
        if (udpLen < 8 || u + udpLen > total) return null
        val srcPort = u16(pkt, u)
        val dstPort = u16(pkt, u + 2)
        // 只接查询（目的端口 53），应答包不进这个方向
        if (dstPort != 53) return null

        return Datagram(
            src = pkt.copyOfRange(12, 16),
            dst = pkt.copyOfRange(16, 20),
            srcPort = srcPort,
            dstPort = dstPort,
            payload = pkt.copyOfRange(u + 8, u + udpLen),
        )
    }

    /**
     * 只认「IPv6 + UDP + 目的端口 53」。
     *
     * 扩展头按链走几跳就够：DNS 查询不会带一堆扩展头，遇到分片（44）或 ICMPv6 直接放弃，
     * 交给上层当成「非 DNS 包」处理，避免把一个 0 长度扩展头读成域名。
     */
    fun parseIpv6Udp(pkt: ByteArray, length: Int): Datagram6? {
        if (length < 48) return null
        if ((pkt[0].toInt() and 0xF0) != 0x60) return null
        val payloadLen = u16(pkt, 4)
        if (payloadLen < 8) return null
        val total = 40 + payloadLen
        if (total > length) return null

        var next = pkt[6].toInt() and 0xFF
        var off = 40
        var hops = 0
        while (next != PROTO_UDP) {
            if (hops++ >= 4) return null
            if (off + 8 > total) return null
            val step = when (next) {
                // 逐跳 / 路由 / 目的选项：长度字段以 8 字节为单位，且不含前 8 字节
                0, 43, 60 -> ((pkt[off + 1].toInt() and 0xFF) + 1) * 8
                // 认证头：长度以 4 字节为单位，且比实际长度少 2
                51 -> ((pkt[off + 1].toInt() and 0xFF) + 2) * 4
                else -> return null
            }
            next = pkt[off].toInt() and 0xFF
            off += step
        }
        if (off + 8 > total) return null

        val udpLen = u16(pkt, off + 4)
        if (udpLen < 8 || off + udpLen > total) return null
        val dstPort = u16(pkt, off + 2)
        if (dstPort != 53) return null

        return Datagram6(
            src = pkt.copyOfRange(8, 24),
            dst = pkt.copyOfRange(24, 40),
            srcPort = u16(pkt, off),
            dstPort = dstPort,
            payload = pkt.copyOfRange(off + 8, off + udpLen),
        )
    }

    /** 只取目的 IPv4，用于判断这个包是不是发往旁路接管列表里的地址。 */
    fun dstIpv4(pkt: ByteArray, length: Int): ByteArray? {
        if (length < 20) return null
        if ((pkt[0].toInt() and 0xFF) shr 4 != 4) return null
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (ihl < 20 || length < ihl) return null
        return pkt.copyOfRange(16, 20)
    }

    fun dstIpv6(pkt: ByteArray, length: Int): ByteArray? {
        if (length < 40) return null
        if ((pkt[0].toInt() and 0xF0) != 0x60) return null
        return pkt.copyOfRange(24, 40)
    }

    /** 是不是 IPv6 且 Version 字段为 6；用于区分「IPv6 包」与「根本不是 IP 包」。 */
    fun isIpv6(pkt: ByteArray, length: Int): Boolean =
        length >= 40 && (pkt[0].toInt() and 0xF0) == 0x60

    /**
     * 内核自己冒出来的 ICMPv6：邻居发现、MLD 报告、路由器通告、重复地址探测。
     * 这些包的目的地要么是 ff02::/16 组播，要么是本机的 ULA 地址，
     * 都不是外部流量，计进「异常」会误报成不支持仅 DNS 路由。
     */
    fun isIpv6KernelNoise(pkt: ByteArray, length: Int): Boolean {
        if (!isIpv6(pkt, length)) return false
        if ((pkt[6].toInt() and 0xFF) == PROTO_ICMPV6) return true
        return (pkt[24].toInt() and 0xFF) == 0xFF
    }

    fun buildIpv4Udp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val out = ByteArray(total)
        out[0] = 0x45.toByte()
        out[1] = 0
        setU16(out, 2, total)
        setU16(out, 4, 0)
        setU16(out, 6, 0x4000)
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        setU16(out, 10, checksumV4(out, 0, 20))

        val u = 20
        setU16(out, u, srcPort)
        setU16(out, u + 2, dstPort)
        setU16(out, u + 4, udpLen)
        setU16(out, u + 6, 0)
        System.arraycopy(payload, 0, out, u + 8, payload.size)
        return out
    }

    fun buildIpv6Udp(
        src: ByteArray,
        dst: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val out = ByteArray(40 + udpLen)
        out[0] = 0x60
        setU16(out, 4, udpLen)
        out[6] = PROTO_UDP.toByte()
        out[7] = 64
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)
        setU16(out, 40, srcPort)
        setU16(out, 42, dstPort)
        setU16(out, 44, udpLen)
        System.arraycopy(payload, 0, out, 48, payload.size)
        val ck = checksumV6Udp(out, src, dst, udpLen)
        // UDP over IPv6 的校验和为 0 表示「未计算」，算出 0 时必须写成 0xFFFF
        setU16(out, 46, if (ck == 0) 0xFFFF else ck)
        return out
    }

    private fun checksumV4(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        val stop = offset + length
        while (i + 1 < stop) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < stop) sum += (buf[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }

    /** 伪首部：src(16) + dst(16) + 长度(4) + 3 个 0 + nextHeader(1)，再叠加整个 UDP 段。 */
    private fun checksumV6Udp(packet: ByteArray, src: ByteArray, dst: ByteArray, udpLen: Int): Int {
        var sum = 0L
        var i = 0
        while (i < 16) {
            sum += ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            sum += ((dst[i].toInt() and 0xFF) shl 8) or (dst[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += (udpLen ushr 16) and 0xFFFF
        sum += udpLen and 0xFFFF
        sum += PROTO_UDP

        // UDP 段（校验和字段此刻还是 0，正好按规范参与求和）
        var j = 40
        val stop = 40 + udpLen
        while (j + 1 < stop) {
            sum += ((packet[j].toInt() and 0xFF) shl 8) or (packet[j + 1].toInt() and 0xFF)
            j += 2
        }
        if (j < stop) sum += (packet[j].toInt() and 0xFF) shl 8

        while ((sum shr 16) > 0) sum = (sum and 0xFFFFL) + (sum shr 16)
        return (sum.inv() and 0xFFFFL).toInt()
    }
}
