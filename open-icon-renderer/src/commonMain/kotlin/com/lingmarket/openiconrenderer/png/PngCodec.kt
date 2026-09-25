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
        var colorType = -1
        var bitDepth = 8
        var palette: IntArray? = null       // 0xFFRRGGBB per entry
        var paletteAlpha: ByteArray? = null // tRNS alpha for palette indices
        var colorKey: IntArray? = null      // tRNS key in 16-bit normalized samples
        val idatChunks = ArrayList<ByteArray>()
        var offset = 8
        while (offset + 8 <= data.size) {
            val length = data.u32BE(offset)
            if (length < 0 || offset + 12L > data.size.toLong() || offset + 12 + length > data.size) break
            val type = data.copyOfRange(offset + 4, offset + 8).decodeToString()
            val chunkData = data.copyOfRange(offset + 8, offset + 8 + length)
            when (type) {
                "IHDR" -> {
                    width = chunkData.u32BE(0)
                    height = chunkData.u32BE(4)
                    bitDepth = chunkData[8].toInt() and 0xFF
                    colorType = chunkData[9].toInt() and 0xFF
                }
                "PLTE" -> {
                    val n = chunkData.size / 3
                    palette = IntArray(n) { i ->
                        val o = i * 3
                        (0xFF shl 24) or
                            ((chunkData[o].toInt() and 0xFF) shl 16) or
                            ((chunkData[o + 1].toInt() and 0xFF) shl 8) or
                            (chunkData[o + 2].toInt() and 0xFF)
                    }
                }
                "tRNS" -> when (colorType) {
                    3 -> paletteAlpha = chunkData.copyOf()
                    0 -> if (chunkData.size >= 2) {
                        colorKey = intArrayOf(sample16(chunkData[0].toInt() and 0xFF, chunkData[1].toInt() and 0xFF))
                    }
                    2 -> if (chunkData.size >= 6) {
                        colorKey = intArrayOf(
                            sample16(chunkData[0].toInt() and 0xFF, chunkData[1].toInt() and 0xFF),
                            sample16(chunkData[2].toInt() and 0xFF, chunkData[3].toInt() and 0xFF),
                            sample16(chunkData[4].toInt() and 0xFF, chunkData[5].toInt() and 0xFF),
                        )
                    }
                    else -> {}
                }
                "IDAT" -> idatChunks.add(chunkData)
                "IEND" -> break
            }
            offset += 12 + length
        }
        val allowedDepths = when (colorType) {
            0 -> byteArrayOf(1, 2, 4, 8, 16)   // grayscale
            2 -> byteArrayOf(8, 16)            // RGB
            3 -> byteArrayOf(1, 2, 4, 8)       // palette
            4 -> byteArrayOf(8, 16)            // gray + alpha
            6 -> byteArrayOf(8, 16)            // RGBA
            else -> return null
        }
        if (width <= 0 || height <= 0 || bitDepth.toByte() !in allowedDepths) return null
        if (colorType == 3 && palette == null) return null
        val compressed = idatChunks.reduce { acc, bytes -> acc + bytes }
        val deflated = unwrapZlib(compressed)
        val inflated = runCatching { inflateRawDeflate(deflated) }.getOrNull() ?: return null
        val samples = when (colorType) { 0 -> 1; 3 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> return null }
        val bitsPerPixel = samples * bitDepth
        val bpp = (bitsPerPixel / 8).coerceAtLeast(1)
        val rowBytes = (width * bitsPerPixel + 7) / 8
        val pixels = IntArray(width * height)
        var inPos = 0
        var prevRow = ByteArray(rowBytes)
        for (y in 0 until height) {
            if (inPos + 1 + rowBytes > inflated.size) return null
            val filter = inflated[inPos++].toInt() and 0xFF
            val row = inflated.copyOfRange(inPos, inPos + rowBytes)
            inPos += rowBytes
            unfilter(filter, row, prevRow, bpp)
            unpackRow(row, pixels, y, width, colorType, bitDepth, palette, paletteAlpha, colorKey)
            prevRow = row
        }
        return RgbaBitmap(width, height, pixels)
    }

    private fun sample16(hi: Int, lo: Int): Int = (hi shl 8) or lo

    @Suppress("LongParameterList")
    private fun unpackRow(
        row: ByteArray,
        pixels: IntArray,
        y: Int,
        width: Int,
        colorType: Int,
        bitDepth: Int,
        palette: IntArray?,
        paletteAlpha: ByteArray?,
        colorKey: IntArray?,
    ) {
        val base = y * width
        val maxSample = (1 shl bitDepth) - 1
        when (colorType) {
            3 -> {
                val pal = palette ?: return
                for (x in 0 until width) {
                    val idx = readBits(row, x * bitDepth, bitDepth)
                    val rgb = pal[idx.coerceAtMost(pal.lastIndex)] and 0x00FFFFFF
                    val a = paletteAlpha?.getOrNull(idx)?.toInt()?.and(0xFF) ?: 0xFF
                    pixels[base + x] = (a shl 24) or rgb
                }
            }
            0 -> {
                for (x in 0 until width) {
                    val v = readBits(row, x * bitDepth, bitDepth)
                    val gray = if (bitDepth >= 8) v shr (bitDepth - 8) else v * 255 / maxSample.coerceAtLeast(1)
                    val key = colorKey != null && colorKey[0] == expand16(v, bitDepth)
                    pixels[base + x] = (if (key) 0 else 0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                }
            }
            4 -> for (x in 0 until width) {
                val vp = x * bitDepth * 2
                val g = sample8(row, vp, bitDepth)
                val a = sample8(row, vp + bitDepth, bitDepth)
                pixels[base + x] = (a shl 24) or (g shl 16) or (g shl 8) or g
            }
            2 -> for (x in 0 until width) {
                val o = x * bitDepth * 3
                val r = sample8(row, o, bitDepth)
                val g = sample8(row, o + bitDepth, bitDepth)
                val b = sample8(row, o + bitDepth * 2, bitDepth)
                val key = colorKey != null &&
                    colorKey[0] == expand16(readBits(row, o, bitDepth), bitDepth) &&
                    colorKey[1] == expand16(readBits(row, o + bitDepth, bitDepth), bitDepth) &&
                    colorKey[2] == expand16(readBits(row, o + bitDepth * 2, bitDepth), bitDepth)
                pixels[base + x] = (if (key) 0 else 0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            6 -> for (x in 0 until width) {
                val o = x * bitDepth * 4
                val r = sample8(row, o, bitDepth)
                val g = sample8(row, o + bitDepth, bitDepth)
                val b = sample8(row, o + bitDepth * 2, bitDepth)
                val a = sample8(row, o + bitDepth * 3, bitDepth)
                pixels[base + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    /** Reads one sample at [bitPos] (MSB-first sub-byte packing) scaled to 8-bit. */
    private fun sample8(row: ByteArray, bitPos: Int, bitDepth: Int): Int {
        val v = readBits(row, bitPos, bitDepth)
        return when {
            bitDepth == 8 -> v
            bitDepth == 16 -> (v ushr 8) and 0xFF
            else -> v * 255 / ((1 shl bitDepth) - 1)
        }
    }

    /** Normalizes a [bitDepth]-bit sample to the 16-bit domain (tRNS comparisons). */
    private fun expand16(v: Int, bitDepth: Int): Int =
        if (bitDepth == 16) v else v * 65535 / ((1 shl bitDepth) - 1)

    private fun readBits(row: ByteArray, bitPos: Int, bits: Int): Int {
        var v = 0
        var p = bitPos
        for (i in 0 until bits) {
            val byte = row[p ushr 3].toInt() and 0xFF
            v = (v shl 1) or ((byte ushr (7 - (p and 7))) and 1)
            p++
        }
        return v
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

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun unwrapZlib(data: ByteArray): ByteArray {
        if (data.size < 6) return data
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        // Proper zlib header check (CM=8 method + checksum of CMF/FLG mod 31) — the old
        // "0x78 prefix" test missed other window classes and fed headered bytes raw.
        if ((cmf and 0x0F) == 8 && ((cmf shl 8) or flg) % 31 == 0) {
            return data.copyOfRange(2, data.size - 4)
        }
        return data
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
