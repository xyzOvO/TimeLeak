package com.chengliu.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

private fun bytes(vararg v: Int): ByteArray = ByteArray(v.size) { v[it].toByte() }

private fun fill(n: Int, v: Int): ByteArray = ByteArray(n) { v.toByte() }

class IpWireTest {

    private val payload = bytes(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)

    private fun sum16(buf: ByteArray, from: Int, to: Int): Int {
        var s = 0L
        var i = from
        while (i + 1 < to) {
            s += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < to) s += (buf[i].toInt() and 0xFF) shl 8
        while ((s shr 16) > 0) s = (s and 0xFFFFL) + (s shr 16)
        return (s and 0xFFFFL).toInt()
    }

    @Test
    fun ipv4UdpRoundTripAndHeaderChecksum() {
        val src = bytes(10, 0, 0, 1)
        val dst = bytes(10, 225, 0, 2)
        val pkt = IpWire.buildIpv4Udp(src, dst, 5555, 53, payload)
        val d = IpWire.parseIpv4Udp(pkt, pkt.size)
        assertNotNull(d)
        assertArrayEquals(src, d!!.src)
        assertArrayEquals(dst, d.dst)
        assertEquals(5555, d.srcPort)
        assertEquals(53, d.dstPort)
        assertArrayEquals(payload, d.payload)
        // 含校验和字段再求一次 16 位反码和，应为 0xFFFF
        assertEquals(0xFFFF, sum16(pkt, 0, 20))
    }

    @Test
    fun ipv6UdpRoundTripAndPseudoHeaderChecksum() {
        val src = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 0x01.toByte() }
        val dst = ByteArray(16).also { it[0] = 0xFD.toByte(); it[15] = 0x02.toByte() }
        val pkt = IpWire.buildIpv6Udp(src, dst, 5555, 53, payload)
        val d = IpWire.parseIpv6Udp(pkt, pkt.size)
        assertNotNull(d)
        assertArrayEquals(src, d!!.src)
        assertArrayEquals(dst, d.dst)
        assertEquals(5555, d.srcPort)
        assertEquals(53, d.dstPort)
        assertArrayEquals(payload, d.payload)

        // 伪首部 + UDP 段（含校验和）反码和应为 0xFFFF
        var s = 0L
        var i = 0
        while (i < 16) {
            s += ((src[i].toInt() and 0xFF) shl 8) or (src[i + 1].toInt() and 0xFF)
            s += ((dst[i].toInt() and 0xFF) shl 8) or (dst[i + 1].toInt() and 0xFF)
            i += 2
        }
        val udpLen = ((pkt[44].toInt() and 0xFF) shl 8) or (pkt[45].toInt() and 0xFF)
        s += udpLen
        s += 17
        var j = 40
        val stop = 40 + udpLen
        while (j + 1 < stop) {
            s += ((pkt[j].toInt() and 0xFF) shl 8) or (pkt[j + 1].toInt() and 0xFF)
            j += 2
        }
        if (j < stop) s += (pkt[j].toInt() and 0xFF) shl 8
        while ((s shr 16) > 0) s = (s and 0xFFFFL) + (s shr 16)
        assertEquals(0xFFFF, (s and 0xFFFFL).toInt())
    }

    @Test
    fun onlyDnsPortIsAccepted() {
        val v4 = IpWire.buildIpv4Udp(bytes(1, 1, 1, 1), bytes(1, 1, 1, 2), 1234, 443, payload)
        assertNull(IpWire.parseIpv4Udp(v4, v4.size))

        val v6 = IpWire.buildIpv6Udp(fill(16, 1), fill(16, 2), 1234, 443, payload)
        assertNull(IpWire.parseIpv6Udp(v6, v6.size))
    }

    @Test
    fun dstAddressExtraction() {
        val v4 = IpWire.buildIpv4Udp(bytes(1, 2, 3, 4), bytes(8, 8, 8, 8), 1, 53, payload)
        assertArrayEquals(bytes(8, 8, 8, 8), IpWire.dstIpv4(v4, v4.size))

        val v6 = IpWire.buildIpv6Udp(fill(16, 1), fill(16, 2), 1, 53, payload)
        assertArrayEquals(fill(16, 2), IpWire.dstIpv6(v6, v6.size))
    }

    @Test
    fun rejectsTruncatedPackets() {
        val pkt = IpWire.buildIpv4Udp(bytes(1, 1, 1, 1), bytes(8, 8, 8, 8), 1, 53, payload)
        assertNull(IpWire.parseIpv4Udp(pkt, 10))
        assertNull(IpWire.parseIpv4Udp(ByteArray(4), 4))
    }
}
