package com.chengliu.app.core

internal fun u16(b: ByteArray, o: Int): Int =
    ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

internal fun setU16(b: ByteArray, o: Int, v: Int) {
    b[o] = ((v ushr 8) and 0xFF).toByte()
    b[o + 1] = (v and 0xFF).toByte()
}
