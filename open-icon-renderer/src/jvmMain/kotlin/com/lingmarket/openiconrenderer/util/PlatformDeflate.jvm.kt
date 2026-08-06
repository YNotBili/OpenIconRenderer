package com.lingmarket.openiconrenderer.util

import java.util.zip.Deflater
import java.util.zip.Inflater

internal actual fun inflateRawDeflate(input: ByteArray, uncompressedSize: Int): ByteArray {
    val inflater = Inflater(true)
    try {
        inflater.setInput(input)
        if (uncompressedSize > 0) {
            val out = ByteArray(uncompressedSize)
            var offset = 0
            while (!inflater.finished() && offset < out.size) {
                val n = inflater.inflate(out, offset, out.size - offset)
                if (n <= 0) break
                offset += n
            }
            return if (offset == out.size) out else out.copyOf(offset)
        }
        val chunks = ArrayList<ByteArray>()
        var total = 0
        val chunk = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n <= 0) {
                if (inflater.needsInput()) break
                if (inflater.needsDictionary()) error("DEFLATE dictionary not supported")
                break
            }
            chunks.add(chunk.copyOf(n))
            total += n
        }
        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    } finally {
        inflater.end()
    }
}

internal actual fun compressZlib(input: ByteArray, level: Int, size: Int): ZlibBytes {
    val n = if (size < 0) input.size else size.coerceIn(0, input.size)
    val deflater = Deflater(level.coerceIn(0, 9))
    try {
        deflater.setInput(input, 0, n)
        deflater.finish()
        val bound = n + (n / 1000) + 16 + 12
        val out = ByteArray(bound.coerceAtLeast(64))
        var offset = 0
        while (!deflater.finished()) {
            if (offset == out.size) {
                val grown = out.copyOf(out.size * 2)
                val written = deflater.deflate(grown, offset, grown.size - offset)
                val full = finishGrow(deflater, grown, offset, written)
                return ZlibBytes(full, full.size)
            }
            val written = deflater.deflate(out, offset, out.size - offset)
            offset += written
        }
        return ZlibBytes(out, offset)
    } finally {
        deflater.end()
    }
}

internal actual fun platformCrc32(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
    // Match libdeflate/zlib: previous finished value in, finished value out; start with 0.
    return crc32Finish(crc32Update(crc.inv(), data, offset, length))
}

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

private fun finishGrow(deflater: Deflater, buf: ByteArray, offset: Int, firstN: Int): ByteArray {
    var o = offset + firstN
    var b = buf
    while (!deflater.finished()) {
        if (o == b.size) b = b.copyOf(b.size * 2)
        o += deflater.deflate(b, o, b.size - o)
    }
    return b.copyOf(o)
}
