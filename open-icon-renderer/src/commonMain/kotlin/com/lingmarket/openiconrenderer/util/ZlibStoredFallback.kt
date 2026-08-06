package com.lingmarket.openiconrenderer.util

/** zlib stream with stored (uncompressed) DEFLATE blocks — portable fallback. */
internal object ZlibStoredFallback {
    fun compress(data: ByteArray): ByteArray {
        val maxBlocks = (data.size + 65534) / 65535
        val out = ByteArray(2 + data.size + maxBlocks * 5 + 4)
        var o = 0
        out[o++] = 0x78
        out[o++] = 0x01
        var offset = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val blockSize = minOf(remaining, 65535)
            val bfinal = if (offset + blockSize >= data.size) 1 else 0
            out[o++] = bfinal.toByte()
            out[o++] = (blockSize and 0xFF).toByte()
            out[o++] = ((blockSize shr 8) and 0xFF).toByte()
            out[o++] = (blockSize.inv() and 0xFF).toByte()
            out[o++] = ((blockSize.inv() shr 8) and 0xFF).toByte()
            data.copyInto(out, o, offset, offset + blockSize)
            o += blockSize
            offset += blockSize
        }
        if (data.isEmpty()) {
            out[o++] = 1
            out[o++] = 0
            out[o++] = 0
            out[o++] = 0xFF.toByte()
            out[o++] = 0xFF.toByte()
        }
        val adler = adler32(data)
        out[o++] = ((adler shr 24) and 0xFF).toByte()
        out[o++] = ((adler shr 16) and 0xFF).toByte()
        out[o++] = ((adler shr 8) and 0xFF).toByte()
        out[o++] = (adler and 0xFF).toByte()
        return out.copyOf(o)
    }

    private fun adler32(data: ByteArray): Int {
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        return (b shl 16) or a
    }
}
