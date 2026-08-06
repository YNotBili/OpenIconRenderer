package com.lingmarket.openiconrenderer.util

internal actual fun packArgbToRgbaFilterNone(
    pixels: IntArray,
    raw: ByteArray,
    width: Int,
    height: Int,
) {
    var o = 0
    var p = 0
    for (y in 0 until height) {
        raw[o++] = 0
        var x = 0
        while (x + 4 <= width) {
            val c0 = pixels[p++]
            val c1 = pixels[p++]
            val c2 = pixels[p++]
            val c3 = pixels[p++]
            raw[o++] = (c0 shr 16).toByte()
            raw[o++] = (c0 shr 8).toByte()
            raw[o++] = c0.toByte()
            raw[o++] = (c0 ushr 24).toByte()
            raw[o++] = (c1 shr 16).toByte()
            raw[o++] = (c1 shr 8).toByte()
            raw[o++] = c1.toByte()
            raw[o++] = (c1 ushr 24).toByte()
            raw[o++] = (c2 shr 16).toByte()
            raw[o++] = (c2 shr 8).toByte()
            raw[o++] = c2.toByte()
            raw[o++] = (c2 ushr 24).toByte()
            raw[o++] = (c3 shr 16).toByte()
            raw[o++] = (c3 shr 8).toByte()
            raw[o++] = c3.toByte()
            raw[o++] = (c3 ushr 24).toByte()
            x += 4
        }
        while (x < width) {
            val c = pixels[p++]
            raw[o++] = (c shr 16).toByte()
            raw[o++] = (c shr 8).toByte()
            raw[o++] = c.toByte()
            raw[o++] = (c ushr 24).toByte()
            x++
        }
    }
}
