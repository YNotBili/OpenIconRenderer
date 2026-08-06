package com.lingmarket.openiconrenderer.image

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.png.PngDecoder
import com.lingmarket.openiconrenderer.util.inflateRawDeflate

internal object ImageDecoder {
    fun decode(data: ByteArray, targetSize: Int = 0): RgbaBitmap? {
        if (data.size < 4) return null
        return when {
            data[0] == 0x89.toByte() && data[1] == 0x50.toByte() -> PngDecoder.decode(data)
            data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() -> JpegDecoder.decode(data)
            data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte() ->
                WebpDecoder.decode(data, targetSize)
            else -> null
        }
    }
}

internal object JpegDecoder {
    fun decode(data: ByteArray): RgbaBitmap? {
        var i = 2
        var width = 0
        var height = 0
        val blocks = ArrayList<ByteArray>()
        while (i + 4 < data.size) {
            if (data[i] != 0xFF.toByte()) {
                i++
                continue
            }
            val marker = data[i + 1].toInt() and 0xFF
            if (marker == 0xD9) break
            if (marker == 0xDA) {
                val scan = data.copyOfRange(i + 2, data.size - 2)
                blocks.add(scan)
                break
            }
            val len = ((data[i + 2].toInt() and 0xFF) shl 8) or (data[i + 3].toInt() and 0xFF)
            if (marker == 0xC0 || marker == 0xC2) {
                height = ((data[i + 5].toInt() and 0xFF) shl 8) or (data[i + 6].toInt() and 0xFF)
                width = ((data[i + 7].toInt() and 0xFF) shl 8) or (data[i + 8].toInt() and 0xFF)
            }
            i += 2 + len
        }
        if (width <= 0 || height <= 0) return null
        // Fallback: produce a neutral placeholder when full Huffman decode is unavailable
        return RgbaBitmap.create(width, height, 0xFF808080.toInt())
    }
}

internal object WebpDecoder {
    fun decode(data: ByteArray, targetSize: Int = 0): RgbaBitmap? {
        val tw = if (targetSize > 0) targetSize else 0
        decodeWebpNative(data, tw, tw)?.let { return it }
        if (data.size < 12 || !data.copyOfRange(0, 4).decodeToString().startsWith("RIFF")) return null
        if (!data.copyOfRange(8, 12).decodeToString().startsWith("WEBP")) return null
        var offset = 12
        while (offset + 8 <= data.size) {
            val tag = data.copyOfRange(offset, offset + 4).decodeToString()
            val size = readLe32(data, offset + 4)
            val chunkStart = offset + 8
            when (tag) {
                "VP8L" -> return decodeVp8l(data.copyOfRange(chunkStart, chunkStart + size))
                "VP8 " -> return decodeVp8Lossy(data.copyOfRange(chunkStart, chunkStart + size))
            }
            offset = chunkStart + size + (size and 1)
        }
        return null
    }

    private fun decodeVp8l(data: ByteArray): RgbaBitmap? {
        if (data.isEmpty() || (data[0].toInt() and 0xFF) != 0x2F) return null
        val bits = (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8) or ((data[3].toInt() and 0xFF) shl 16) or ((data[4].toInt() and 0xFF) shl 24)
        val width = (bits and 0x3FFF) + 1
        val height = ((bits shr 14) and 0x3FFF) + 1
        val alpha = (bits shr 28) and 1
        val version = (bits shr 29) and 1
        if (version != 0) return null
        val stream = data.copyOfRange(5, data.size)
        val inflated = runCatching { inflateRawDeflate(stream) }.getOrNull() ?: stream
        val pixels = IntArray(width * height)
        if (inflated.size >= width * height * 4) {
            for (i in pixels.indices) {
                val p = i * 4
                val r = inflated[p].toInt() and 0xFF
                val g = inflated[p + 1].toInt() and 0xFF
                val b = inflated[p + 2].toInt() and 0xFF
                val a = if (alpha == 1) inflated[p + 3].toInt() and 0xFF else 255
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            return RgbaBitmap(width, height, pixels)
        }
        return RgbaBitmap.create(width, height, 0xFF404040.toInt())
    }

    private fun decodeVp8Lossy(data: ByteArray): RgbaBitmap? {
        if (data.size < 10) return null
        val frameTag = data[3].toInt() and 0xFF
        val keyframe = frameTag and 1 == 0
        if (!keyframe) return null
        val bits = (data[6].toInt() and 0xFF) or ((data[7].toInt() and 0xFF) shl 8) or ((data[8].toInt() and 0xFF) shl 16)
        val width = bits and 0x3FFF
        val height = (bits shr 16) and 0x3FFF
        if (width <= 0 || height <= 0) return null
        return RgbaBitmap.create(width, height, 0xFF606060.toInt())
    }

    private fun readLe32(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
            ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or
            ((data[offset + 3].toInt() and 0xFF) shl 24)
}
