package com.chengliu.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 测试辅助：把 Int 字面量转成 ByteArray，避免满屏 .toByte()。 */
private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

class DnsWireTest {

    private fun header(qd: Int, ar: Int): ByteArray = bytes(
        0x12, 0x34, 0x01, 0x00,
        qd shr 8, qd and 0xFF,
        0, 0, 0, 0,
        ar shr 8, ar and 0xFF,
    )

    private fun question(name: String, qtype: Int = 1): ByteArray {
        val o = ByteArrayOutputStream()
        for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            o.write(b.size)
            o.write(b)
        }
        o.write(0)
        o.write(bytes(0x00, qtype, 0x00, 0x01))
        return o.toByteArray()
    }

    /** 一条带 ECS 的 OPT：rdlen=12，其中 option(code=8,len=8)+8 字节数据。 */
    private fun ecsOpt(): ByteArray = bytes(
        0x00,                                     // name = root
        0x00, 0x29,                               // type = OPT(41)
        0x10, 0x00,                               // class = 4096
        0x00, 0x00, 0x00, 0x00,                   // ttl
        0x00, 0x0C,                               // rdlen = 12
        0x00, 0x08, 0x00, 0x08,                   // option code 8, len 8
        0x00, 0x01, 24, 0, 1, 2, 3, 0,            // family 1, src/scope, address
    )

    @Test
    fun parseQueryReadsNameTypeAndQuestionEnd() {
        val q = header(1, 0) + question("Ads.Example.COM")
        val p = DnsWire.parseQuery(q, q.size)
        assertNotNull(p)
        assertEquals("ads.example.com", p!!.name)
        assertEquals(1, p.qtype)
        assertEquals(1, p.qclass)
        assertEquals(q.size, p.questionEnd)
    }

    @Test
    fun parseQueryRejectsTruncatedOrMultiQuestion() {
        val q = header(1, 0) + question("example.com")
        assertNull(DnsWire.parseQuery(q, q.size - 2))
        assertNull(DnsWire.parseQuery(header(2, 0) + question("example.com"), q.size))
        assertNull(DnsWire.parseQuery(bytes(1, 2, 3), 3))
    }

    @Test
    fun nxDomainSetsQrAndRcodeAndZeroesCounts() {
        val q = header(1, 0) + question("ads.example.com")
        val qi = DnsWire.parseQuery(q, q.size)!!
        val r = DnsWire.nxDomain(q, qi)
        assertEquals(qi.questionEnd, r.size)
        val flags = ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF)
        assertTrue("QR 必须置位", flags and 0x8000 != 0)
        assertEquals("RCODE 必须是 NXDOMAIN", 3, flags and 0x000F)
        assertEquals(0, ((r[6].toInt() and 0xFF) shl 8) or (r[7].toInt() and 0xFF))
        assertEquals(0, ((r[8].toInt() and 0xFF) shl 8) or (r[9].toInt() and 0xFF))
    }

    @Test
    fun servfailSetsRcodeTwo() {
        val q = header(1, 0) + question("slow.example.com")
        val qi = DnsWire.parseQuery(q, q.size)!!
        val r = DnsWire.servfail(q, qi)
        val flags = ((r[2].toInt() and 0xFF) shl 8) or (r[3].toInt() and 0xFF)
        assertTrue("QR 必须置位", flags and 0x8000 != 0)
        assertEquals("RCODE 必须是 SERVFAIL", 2, flags and 0x000F)
    }

    @Test
    fun stripEcsRemovesOptionAndZeroesRdlen() {
        val q = header(1, 1) + question("example.com") + ecsOpt()
        val stripped = DnsWire.stripEcs(q, q.size)
        assertNotNull(stripped)
        assertEquals("应恰好移除 12 字节 ECS 选项", q.size - 12, stripped!!.size)

        val optStart = 12 + question("example.com").size
        val rdlen = ((stripped[optStart + 9].toInt() and 0xFF) shl 8) or
            (stripped[optStart + 10].toInt() and 0xFF)
        assertEquals("OPT 的 RDLEN 必须同步归零", 0, rdlen)
    }

    @Test
    fun stripEcsReturnsNullWhenNothingToStrip() {
        val q = header(1, 0) + question("example.com")
        assertNull(DnsWire.stripEcs(q, q.size))
    }
}
