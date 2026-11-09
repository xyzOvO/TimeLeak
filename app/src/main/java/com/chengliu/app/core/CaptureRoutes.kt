package com.chengliu.app.core

import java.net.InetAddress
import java.util.Locale

/**
 * 需要吸进隧道的地址集合（内置公共 DNS + 用户自定义目标 + 常见加密 DNS 网段）。
 *
 * 之所以要有这个类，而不直接把字符串丢给 Builder：
 * 1. 建隧道要的是 CIDR 列表，判包要的是「这个目的地址在不在里面」，
 *    后者如果每次都拼字符串比较，热路径上会白白分配一堆对象。
 * 2. 系统「私人 DNS」指定了服务器时，它的地址必须从接管里排除。
 *    否则 DoT 的 853 会被隧道吞掉，严格模式下整机解析直接停摆
 *    （私人 DNS 没有「失败就退回明文」这一步，这是最容易自伤的地方）。
 */
class CaptureRoutes private constructor(
    /** 归一化后的 CIDR 字符串，直接喂给 VpnService.Builder.addRoute。 */
    val entries: List<String>,
    private val v4Net: IntArray,
    private val v4Mask: IntArray,
    private val v6Net: Array<ByteArray>,
    private val v6Len: IntArray,
) {

    val size: Int get() = entries.size

    fun covers(ip: ByteArray): Boolean = when (ip.size) {
        4 -> coversV4(ip)
        16 -> coversV6(ip)
        else -> false
    }

    fun coversV4(ip: ByteArray): Boolean {
        if (ip.size != 4) return false
        val v = ((ip[0].toInt() and 0xFF) shl 24) or
            ((ip[1].toInt() and 0xFF) shl 16) or
            ((ip[2].toInt() and 0xFF) shl 8) or
            (ip[3].toInt() and 0xFF)
        for (i in v4Net.indices) {
            if (v and v4Mask[i] == v4Net[i]) return true
        }
        return false
    }

    fun coversV6(ip: ByteArray): Boolean {
        if (ip.size != 16) return false
        for (i in v6Net.indices) {
            if (matchPrefix(ip, v6Net[i], v6Len[i])) return true
        }
        return false
    }

    companion object {
        val EMPTY = CaptureRoutes(emptyList(), IntArray(0), IntArray(0), emptyArray(), IntArray(0))

        /**
         * @param raw 任意顺序的条目，可含非法项（会被丢掉）
         * @param exclude 必须放行的地址（系统私人 DNS 的服务器）
         */
        fun build(raw: Collection<String>, exclude: Collection<String> = emptyList()): CaptureRoutes {
            val excluded = exclude.mapNotNull { literal(it) }
            val norm = LinkedHashSet<String>()
            for (item in raw) {
                val cidr = normalize(item) ?: continue
                // 覆盖了被排除地址的整条规则直接不要，不做「拆网段」那种自作聪明的事：
                // 私人 DNS 的地址本来就只有一两个 /32，拆开反而容易留下半截漏洞。
                if (excluded.any { covers(cidr, it) }) continue
                norm.add(cidr)
            }

            val v4n = ArrayList<Int>(norm.size)
            val v4m = ArrayList<Int>(norm.size)
            val v6n = ArrayList<ByteArray>(norm.size)
            val v6l = ArrayList<Int>(norm.size)
            for (cidr in norm) {
                val slash = cidr.indexOf('/')
                val addr = cidr.substring(0, slash)
                val prefix = cidr.substring(slash + 1).toIntOrNull() ?: continue
                val bytes = literal(addr) ?: continue
                if (bytes.size == 4) {
                    val v = ((bytes[0].toInt() and 0xFF) shl 24) or
                        ((bytes[1].toInt() and 0xFF) shl 16) or
                        ((bytes[2].toInt() and 0xFF) shl 8) or
                        (bytes[3].toInt() and 0xFF)
                    val mask = if (prefix <= 0) 0 else (-1 shl (32 - prefix))
                    v4n.add(v and mask)
                    v4m.add(mask)
                } else {
                    v6n.add(bytes)
                    v6l.add(prefix)
                }
            }
            return CaptureRoutes(
                norm.toList(),
                v4n.toIntArray(), v4m.toIntArray(),
                v6n.toTypedArray(), v6l.toIntArray(),
            )
        }

        /** 接受 a.b.c.d、a.b.c.d/24~32、IPv6 字面量、IPv6/64~128。 */
        fun normalize(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            val slash = s.indexOf('/')
            val addr = if (slash > 0) s.substring(0, slash).trim() else s
            val prefix = if (slash > 0) s.substring(slash + 1).trim().toIntOrNull() ?: return null else -1
            val bytes = literal(addr) ?: return null
            return if (bytes.size == 4) {
                val p = if (prefix < 0) 32 else prefix
                if (p !in 24..32) return null
                if (isPrivate4(bytes)) return null
                "$addr/$p"
            } else {
                val p = if (prefix < 0) 128 else prefix
                if (p !in 64..128) return null
                if (isPrivate6(bytes)) return null
                "${addr.lowercase(Locale.ROOT)}/$p"
            }
        }

        /**
         * 字面量转字节；域名一律返回 null。
         *
         * IPv4 必须自己逐段校验：`InetAddress.getByName("a.b.c.d")` 对非数字串会
         * 走真正的 DNS 解析，而这方法会在建隧道的主线程上被调用。
         */
        fun literal(raw: String): ByteArray? {
            val s = raw.trim().trim('[', ']')
            if (s.isEmpty()) return null
            if (s.contains(':')) {
                return try {
                    val b = InetAddress.getByName(s).address
                    if (b.size == 16) b else null
                } catch (_: Exception) {
                    null
                }
            }
            val parts = s.split('.')
            if (parts.size != 4) return null
            val out = ByteArray(4)
            for (i in 0..3) {
                val p = parts[i]
                if (p.isEmpty() || p.length > 3 || !p.all { it.isDigit() }) return null
                val v = p.toIntOrNull() ?: return null
                if (v !in 0..255) return null
                out[i] = v.toByte()
            }
            return out
        }

        private fun covers(cidr: String, ip: ByteArray): Boolean {
            val slash = cidr.indexOf('/')
            val bytes = literal(cidr.substring(0, slash)) ?: return false
            val prefix = cidr.substring(slash + 1).toIntOrNull() ?: return false
            if (bytes.size != ip.size) return false
            return matchPrefix(ip, bytes, prefix)
        }

        private fun matchPrefix(ip: ByteArray, net: ByteArray, prefix: Int): Boolean {
            if (ip.size != net.size) return false
            val full = prefix / 8
            for (i in 0 until full) {
                if (ip[i] != net[i]) return false
            }
            val rest = prefix % 8
            if (rest == 0) return true
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (ip[full].toInt() and mask) == (net[full].toInt() and mask)
        }

        private fun isPrivate4(b: ByteArray): Boolean {
            val a = b[0].toInt() and 0xFF
            val c = b[1].toInt() and 0xFF
            if (a == 0 || a == 10 || a == 127) return true
            if (a == 169 && c == 254) return true
            if (a == 192 && c == 168) return true
            if (a == 172 && c in 16..31) return true
            if (a == 100 && c in 64..127) return true
            if (a == 224 || a >= 240) return true
            return false
        }

        /** 回环、链路本地、ULA、组播、IPv4 映射段都不该出现在接管列表里。 */
        private fun isPrivate6(b: ByteArray): Boolean {            val b0 = b[0].toInt() and 0xFF
            val b1 = b[1].toInt() and 0xFF
            val allZero = b.all { it.toInt() == 0 }
            if (allZero) return true
            // ::1
            if ((0 until 15).all { b[it].toInt() == 0 } && b[15].toInt() == 1) return true
            if (b0 == 0xFF) return true
            if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
            if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true
            if ((b0 and 0xFE) == 0xFC) return true
            // ::ffff:a.b.c.d
            if ((0 until 10).all { b[it].toInt() == 0 } &&
                (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
            ) {
                return true
            }
            return false
        }
    }
}

/**
 * 把「要接管哪些地址」这件事集中在一处，服务与界面用同一套算法，
 * 免得界面上写着「已接管」而隧道里其实漏掉一条。
 */
object CapturePlan {

    /** 全量接管集合，按内置 v4 / 内置 v6 / 服务商网段 / 用户自定义 的顺序合并去重。 */
    fun all(leakTargets: Collection<String>): List<String> {
        val raw = LinkedHashSet<String>(Privacy.BYPASS_DNS.size + Privacy.BYPASS_DNS6.size + 32)
        raw.addAll(Privacy.BYPASS_DNS)
        raw.addAll(Privacy.BYPASS_DNS6)
        raw.addAll(DohEndpoints.PREFIXES)
        raw.addAll(leakTargets)
        return raw.toList()
    }

    /**
     * @param excludeIps 需要放行的具体地址（系统私人 DNS 解析出的真实 IP，纯 IP，不带掩码）
     * @return first = 实际生效的路由；second = 因为系统私人 DNS 被排除掉的地址
     */
    fun resolve(
        leakTargets: Collection<String>,
        excludeIps: List<String>,
    ): Pair<CaptureRoutes, List<String>> {
        val raw = all(leakTargets)
        if (excludeIps.isEmpty()) return CaptureRoutes.build(raw) to emptyList()
        val unfiltered = CaptureRoutes.build(raw)
        val dropped = excludeIps.filter { ip ->
            val bytes = CaptureRoutes.literal(ip)
            bytes != null && unfiltered.covers(bytes)
        }
        return CaptureRoutes.build(raw, dropped) to dropped
    }

    /**
     * 界面用：只做内置表 / 字面量匹配，不发起任何网络解析（避免卡 UI）。
     * 服务侧请改用 [resolve] 的 IP 列表版本，那里会先把私人 DNS 主机名解析成真实地址。
     */
    fun resolve(
        leakTargets: Collection<String>,
        privateDns: PrivateDns.State,
    ): Pair<CaptureRoutes, List<String>> = resolve(leakTargets, PrivateDns.addresses(privateDns))
}
