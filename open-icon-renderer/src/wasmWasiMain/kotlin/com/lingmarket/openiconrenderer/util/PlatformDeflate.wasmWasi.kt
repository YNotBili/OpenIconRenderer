package com.lingmarket.openiconrenderer.util

internal actual fun inflateRawDeflate(input: ByteArray, uncompressedSize: Int): ByteArray =
    DeflateInflater.inflate(input)

internal actual fun compressZlib(input: ByteArray, level: Int, size: Int): ZlibBytes {
    val n = if (size < 0) input.size else size.coerceIn(0, input.size)
    val out = ZlibStoredFallback.compress(if (n == input.size) input else input.copyOf(n))
    return ZlibBytes(out, out.size)
}

internal actual fun platformCrc32(crc: Int, data: ByteArray, offset: Int, length: Int): Int =
    crc32Finish(crc32Update(crc.inv(), data, offset, length))

internal actual fun platformAdler32(adler: Int, data: ByteArray, offset: Int, length: Int): Int {
    var a = adler and 0xFFFF
    var b = (adler ushr 16) and 0xFFFF
    val end = offset + length
    var i = offset
    while (i < end) {
        a += data[i].toInt() and 0xFF
        if (a >= 65521) a -= 65521
        b += a
        if (b >= 65521) b -= 65521
        i++
    }
    return (b shl 16) or a
}
