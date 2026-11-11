package com.chengliu.app.core

import java.util.Locale

/**
 * 只需要 DNS 报文里两件事：读出 question 的域名、拼一个 NXDOMAIN 应答。
 * 其余字段一律不解释，这样既轻，也不会因为碰到不认识的扩展记录而误判。
 */
object DnsWire {
    private const val HEADER = 12
    private const val FLAG_QR = 0x8000
    private const val KEEP_OPCODE_AND_RD = 0x7900
    private const val FLAG_RA = 0x0080
    private const val RCODE_NXDOMAIN = 0x0003
    private const val RCODE_SERVFAIL = 0x0002

    data class Query(
        val name: String,
        val qtype: Int,
        val qclass: Int,
        /** question 段结束位置，也是构造应答时需要原样拷回的长度 */
        val questionEnd: Int,
    )

    fun parseQuery(msg: ByteArray, length: Int = msg.size): Query? {
        if (length < HEADER + 5) return null
        // 多 question 的报文极罕见，解析不了就交给上游原样转发
        if (u16(msg, 4) != 1) return null
        val (name, nameEnd) = decodeName(msg, HEADER, length) ?: return null
        if (nameEnd + 4 > length) return null
        // question 段里用压缩指针时，nxDomain / servfail 只拷到「指针 + 4」，
        // 指针指向的标签会被截掉，客户端拿到越界应答只能超时重试。
        // 这种报文交给上游原样转发，不本地构造应答。
        for (i in HEADER until nameEnd) {
            if ((msg[i].toInt() and 0xC0) == 0xC0) return null
        }
        return Query(name, u16(msg, nameEnd), u16(msg, nameEnd + 2), nameEnd + 4)
    }

    /** 保留原 question 段，只改 header：置 QR、RA，清空回答数，rcode=NXDOMAIN。 */
    fun nxDomain(msg: ByteArray, query: Query): ByteArray {
        val out = ByteArray(query.questionEnd)
        System.arraycopy(msg, 0, out, 0, query.questionEnd)
        val flags = FLAG_QR or (u16(msg, 2) and KEEP_OPCODE_AND_RD) or FLAG_RA or RCODE_NXDOMAIN
        setU16(out, 2, flags)
        setU16(out, 6, 0)
        setU16(out, 8, 0)
        setU16(out, 10, 0)
        return out
    }

    /**
     * 上游解析失败时回 SERVFAIL，取代「静默丢包」。
     * 静默丢包会让客户端一直等超时再重试，形成查询风暴（实测 16 小时 124 万次拦截）；
     * SERVFAIL 让客户端立刻失败退避，不再傻等。rcode / 长度构造与 nxDomain 完全对称。
     */
    fun servfail(msg: ByteArray, query: Query): ByteArray {
        val out = ByteArray(query.questionEnd)
        System.arraycopy(msg, 0, out, 0, query.questionEnd)
        val flags = FLAG_QR or (u16(msg, 2) and KEEP_OPCODE_AND_RD) or FLAG_RA or RCODE_SERVFAIL
        setU16(out, 2, flags)
        setU16(out, 6, 0)
        setU16(out, 8, 0)
        setU16(out, 10, 0)
        return out
    }

    /**
     * 去掉 EDNS Client Subnet（OPT 里的 option code 8）。
     *
     * 上游拿到 ECS 就能把你的位置还原到区县级，这是最常被忽略的一类泄露；
     * 而绝大多数递归解析并不需要它。只删这一项，其余 OPT 内容（Cookie、Padding）
     * 原样保留，避免惹出兼容问题。
     *
     * 返回 null 表示报文里没有 ECS，调用方直接复用原数组即可。
     */
    fun stripEcs(msg: ByteArray, length: Int = msg.size): ByteArray? {
        if (length < HEADER) return null
        val qd = u16(msg, 4)
        val an = u16(msg, 6)
        val ns = u16(msg, 8)
        val ar = u16(msg, 10)
        if (qd == 0 || ar == 0) return null

        var pos = HEADER
        repeat(qd) {
            pos = skipName(msg, pos, length) ?: return null
            if (pos + 4 > length) return null
            pos += 4
        }
        repeat(an + ns) {
            pos = skipRecord(msg, pos, length) ?: return null
        }

        var optRdlenAt = -1
        var rdataStart = -1
        var rdlen = 0
        repeat(ar) {
            val nameEnd = skipName(msg, pos, length) ?: return null
            if (nameEnd + 10 > length) return null
            val type = u16(msg, nameEnd)
            val len = u16(msg, nameEnd + 8)
            val rdata = nameEnd + 10
            if (rdata + len > length) return null
            // 41 = OPT，一个报文最多一条
            if (type == 41 && rdataStart < 0) {
                optRdlenAt = nameEnd + 8
                rdataStart = rdata
                rdlen = len
            }
            pos = rdata + len
        }
        if (rdataStart < 0 || rdlen < 4) return null

        val cuts = ArrayList<Int>(4)
        var i = rdataStart
        val end = rdataStart + rdlen
        while (i + 4 <= end) {
            val code = u16(msg, i)
            val len = u16(msg, i + 2)
            if (i + 4 + len > end) break
            if (code == 8) {
                cuts.add(i)
                cuts.add(i + 4 + len)
            }
            i += 4 + len
        }
        if (cuts.isEmpty()) return null

        var removed = 0
        var k = 0
        while (k < cuts.size) {
            removed += cuts[k + 1] - cuts[k]
            k += 2
        }

        val out = ByteArray(length - removed)
        System.arraycopy(msg, 0, out, 0, rdataStart)
        var src = rdataStart
        var dst = rdataStart
        k = 0
        while (k < cuts.size) {
            val keep = cuts[k] - src
            if (keep > 0) {
                System.arraycopy(msg, src, out, dst, keep)
                dst += keep
            }
            src = cuts[k + 1]
            k += 2
        }
        val tail = length - src
        if (tail > 0) System.arraycopy(msg, src, out, dst, tail)
        setU16(out, optRdlenAt, rdlen - removed)
        return out
    }

    /** 跳过一个域名，返回其后第一个字节的下标；压缩指针只跳到指针结尾。 */
    private fun skipName(msg: ByteArray, start: Int, length: Int): Int? {
        var pos = start
        var hops = 0
        while (pos < length) {
            if (hops++ > 128) return null
            val n = msg[pos].toInt() and 0xFF
            if (n == 0) return pos + 1
            if ((n and 0xC0) == 0xC0) return if (pos + 2 <= length) pos + 2 else null
            if ((n and 0xC0) != 0) return null
            pos += 1 + n
        }
        return null
    }

    private fun skipRecord(msg: ByteArray, start: Int, length: Int): Int? {
        val nameEnd = skipName(msg, start, length) ?: return null
        if (nameEnd + 10 > length) return null
        val next = nameEnd + 10 + u16(msg, nameEnd + 8)
        return if (next <= length) next else null
    }

    /** 支持压缩指针，但查询段通常不用；仍实现以防某些客户端发了带指针的报文。 */
    private fun decodeName(msg: ByteArray, start: Int, length: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var jumped = false
        var hops = 0
        while (true) {
            if (hops++ > 96 || pos < 0 || pos >= length) return null
            val n = msg[pos].toInt() and 0xFF
            if (n == 0) {
                pos++
                if (!jumped) end = pos
                break
            }
            when {
                (n and 0xC0) == 0xC0 -> {
                    if (pos + 1 >= length) return null
                    val ptr = ((n and 0x3F) shl 8) or (msg[pos + 1].toInt() and 0xFF)
                    if (!jumped) end = pos + 2
                    pos = ptr
                    jumped = true
                }
                (n and 0xC0) != 0 -> return null
                else -> {
                    if (pos + 1 + n > length) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 1..n) {
                        val c = msg[pos + i].toInt() and 0xFF
                        sb.append(if (c in 32..126) c.toChar() else '?')
                    }
                    pos += 1 + n
                    if (sb.length > 253) return null
                }
            }
        }
        if (sb.isEmpty() || end < 0) return null
        return sb.toString().lowercase(Locale.ROOT) to end
    }
}
