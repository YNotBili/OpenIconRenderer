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
        for (x in 0 until width) {
            val c = pixels[p++]
            raw[o++] = (c shr 16).toByte()
            raw[o++] = (c shr 8).toByte()
            raw[o++] = c.toByte()
            raw[o++] = (c ushr 24).toByte()
        }
    }
}
