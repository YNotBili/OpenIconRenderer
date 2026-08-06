package com.lingmarket.openiconrenderer.png

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.util.compressZlib
import com.lingmarket.openiconrenderer.util.inflateRawDeflate
import com.lingmarket.openiconrenderer.util.packArgbToRgbaFilterNone
import com.lingmarket.openiconrenderer.util.platformCrc32

private fun ByteArray.u32BE(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

internal object PngDecoder {
    fun decode(data: ByteArray): RgbaBitmap? {
        if (data.size < 8 || !data.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return null
        var width = 0
        var height = 0
        var colorType = 0
        var bitDepth = 8
        val idatChunks = ArrayList<ByteArray>()
        var offset = 8
        while (offset + 8 <= data.size) {
            val length = data.u32BE(offset)
            val type = data.copyOfRange(offset + 4, offset + 8).decodeToString()
            val chunkData = data.copyOfRange(offset + 8, offset + 8 + length)
            when (type) {
                "IHDR" -> {
                    width = chunkData.u32BE(0)
                    height = chunkData.u32BE(4)
                    bitDepth = chunkData[8].toInt() and 0xFF
                    colorType = chunkData[9].toInt() and 0xFF
                }
                "IDAT" -> idatChunks.add(chunkData)
                "IEND" -> break
            }
            offset += 12 + length
        }
        if (width <= 0 || height <= 0 || colorType !in setOf(0, 2, 6) || bitDepth != 8) return null
        val compressed = idatChunks.reduce { acc, bytes -> acc + bytes }
        val deflated = unwrapZlib(compressed)
        val inflated = inflateRawDeflate(deflated)
        val bpp = when (colorType) {
            0 -> 1
            2 -> 3
            6 -> 4
            else -> return null
        }
        val rowBytes = width * bpp
        val pixels = IntArray(width * height)
        var inPos = 0
        var prevRow = ByteArray(rowBytes)
        for (y in 0 until height) {
            val filter = inflated[inPos++].toInt() and 0xFF
            val row = inflated.copyOfRange(inPos, inPos + rowBytes)
            inPos += rowBytes
            unfilter(filter, row, prevRow, bpp)
            decodeRow(row, pixels, y, width, colorType)
            prevRow = row
        }
        return RgbaBitmap(width, height, pixels)
    }

    private fun unfilter(filter: Int, row: ByteArray, prev: ByteArray, bpp: Int) {
        when (filter) {
            1 -> for (i in bpp until row.size) row[i] = (row[i] + row[i - bpp]).toByte()
            2 -> for (i in row.indices) row[i] = (row[i] + prev[i]).toByte()
            3 -> for (i in row.indices) {
                val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                row[i] = (row[i] + ((left + up) / 2)).toByte()
            }
            4 -> for (i in row.indices) {
                val left = if (i >= bpp) row[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                row[i] = (row[i] + paethPredictor(left, up, upLeft)).toByte()
            }
        }
    }

    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }

    private fun decodeRow(row: ByteArray, pixels: IntArray, y: Int, width: Int, colorType: Int) {
        var pos = 0
        for (x in 0 until width) {
            pixels[y * width + x] = when (colorType) {
                6 -> {
                    val r = row[pos++].toInt() and 0xFF
                    val g = row[pos++].toInt() and 0xFF
                    val b = row[pos++].toInt() and 0xFF
                    val a = row[pos++].toInt() and 0xFF
                    (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                2 -> {
                    val r = row[pos++].toInt() and 0xFF
                    val g = row[pos++].toInt() and 0xFF
                    val b = row[pos++].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                else -> {
                    val v = row[pos++].toInt() and 0xFF
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }
    }

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun unwrapZlib(data: ByteArray): ByteArray {
        if (data.size < 6) return data
        if ((data[0].toInt() and 0xFF) != 0x78) return data
        return data.copyOfRange(2, data.size - 4)
    }
}

/**
 * Fast PNG encoder for icons: filter-None + zlib (libdeflate level 3).
 * Scratch buffers are reused across encodes.
 */
internal object PngEncoder {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val TYPE_IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    private val TYPE_IDAT = byteArrayOf(0x49, 0x44, 0x41, 0x54)
    private val TYPE_IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
    private val EMPTY = ByteArray(0)

    /** Balanced compression (libdeflate / JVM Deflater). */
    private const val ZLIB_LEVEL = 3

    /** Full raw frame for deflated path (grows, reused). */
    private var rawScratch: ByteArray? = null
    /** Reused final PNG buffer (grows). */
    private var outScratch: ByteArray? = null

    fun encode(bitmap: RgbaBitmap): ByteArray = encodeDeflated(bitmap, level = ZLIB_LEVEL)

    private fun encodeDeflated(bitmap: RgbaBitmap, level: Int): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val stride = 1 + width * 4
        val rawLen = height * stride
        var raw = rawScratch
        if (raw == null || raw.size < rawLen) {
            raw = ByteArray(rawLen)
            rawScratch = raw
        }
        packArgbToRgbaFilterNone(bitmap.pixels, raw, width, height)
        val idat = compressZlib(raw, level = level, size = rawLen)

        val need = 8 + chunkSize(13) + chunkSize(idat.length) + chunkSize(0)
        var out = outScratch
        if (out == null || out.size < need) {
            out = ByteArray(need)
            outScratch = out
        }
        var pos = 0
        PNG_SIGNATURE.copyInto(out, pos)
        pos += 8
        val ihdr = ByteArray(13)
        writeU32(ihdr, 0, width)
        writeU32(ihdr, 4, height)
        ihdr[8] = 8
        ihdr[9] = 6
        pos = writeChunk(out, pos, TYPE_IHDR, ihdr, 13)
        pos = writeChunk(out, pos, TYPE_IDAT, idat.bytes, idat.length)
        pos = writeChunk(out, pos, TYPE_IEND, EMPTY, 0)
        // Keep scratch for reuse; return a tight copy of the used prefix.
        return out.copyOf(pos)
    }

    private fun chunkSize(dataLen: Int): Int = 12 + dataLen

    private fun writeChunk(out: ByteArray, offset: Int, type: ByteArray, data: ByteArray, dataLen: Int): Int {
        var pos = offset
        writeU32(out, pos, dataLen)
        pos += 4
        val crcStart = pos
        type.copyInto(out, pos)
        pos += 4
        if (dataLen > 0) {
            data.copyInto(out, pos, 0, dataLen)
            pos += dataLen
        }
        val crc = platformCrc32(0, out, crcStart, 4 + dataLen)
        writeU32(out, pos, crc)
        return pos + 4
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value shr 24) and 0xFF).toByte()
        target[offset + 1] = ((value shr 16) and 0xFF).toByte()
        target[offset + 2] = ((value shr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }
}
