package com.lingmarket.openiconrenderer.png

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.util.compressZlib
import com.lingmarket.openiconrenderer.util.inflateRawDeflate
import com.lingmarket.openiconrenderer.util.platformAdler32
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
 * Fast PNG encoder for icons: filter-None + zlib stored blocks streamed into IDAT
 * (no full-frame raw scratch). Adler-32 / CRC-32 via libdeflate on native.
 */
internal object PngEncoder {
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val TYPE_IHDR = byteArrayOf(0x49, 0x48, 0x44, 0x52)
    private val TYPE_IDAT = byteArrayOf(0x49, 0x44, 0x41, 0x54)
    private val TYPE_IEND = byteArrayOf(0x49, 0x45, 0x4E, 0x44)
    private val EMPTY = ByteArray(0)

    /** One scanline scratch: filter byte + RGBA. */
    private var rowScratch: ByteArray? = null
    /** Full raw frame for deflated path (grows). */
    private var rawScratch: ByteArray? = null
    /** Reused final PNG buffer (grows). */
    private var outScratch: ByteArray? = null

    fun encode(bitmap: RgbaBitmap): ByteArray {
        // Large icons: libdeflate level 1 keeps IDAT small (faster write) vs 16MB stored.
        // Small icons: stored stream avoids a full-frame raw buffer.
        return if (bitmap.width * bitmap.height >= 1024 * 1024) {
            encodeDeflated(bitmap, level = 1)
        } else {
            encodeStored(bitmap)
        }
    }

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
        return out.copyOf(pos)
    }

    private fun packArgbToRgbaFilterNone(pixels: IntArray, raw: ByteArray, width: Int, height: Int) {
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

    private fun encodeStored(bitmap: RgbaBitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val stride = 1 + width * 4
        val rawLen = height * stride
        val maxBlocks = (rawLen + 65534) / 65535
        val idatLen = 2 + rawLen + maxBlocks * 5 + 4
        val need = 8 + chunkSize(13) + chunkSize(idatLen) + chunkSize(0)

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

        // IDAT header (length patched after streaming)
        val idatLenPos = pos
        pos += 4
        val crcStart = pos
        TYPE_IDAT.copyInto(out, pos)
        pos += 4
        val zlibStart = pos

        // zlib header: 78 01 (stored-friendly)
        out[pos++] = 0x78
        out[pos++] = 0x01

        var row = rowScratch
        if (row == null || row.size < stride) {
            row = ByteArray(stride)
            rowScratch = row
        }

        val pixels = bitmap.pixels
        var adler = 1
        var blockOpen = false
        var blockStart = 0
        var blockLen = 0
        var remainingRaw = rawLen
        var pix = 0

        fun closeBlock(final: Boolean) {
            if (!blockOpen) return
            val bfinal = if (final) 1 else 0
            out[blockStart] = bfinal.toByte()
            out[blockStart + 1] = (blockLen and 0xFF).toByte()
            out[blockStart + 2] = ((blockLen shr 8) and 0xFF).toByte()
            out[blockStart + 3] = (blockLen.inv() and 0xFF).toByte()
            out[blockStart + 4] = ((blockLen.inv() shr 8) and 0xFF).toByte()
            blockOpen = false
            blockLen = 0
        }

        fun ensureBlock() {
            if (blockOpen) return
            blockStart = pos
            pos += 5 // header filled in closeBlock
            blockOpen = true
            blockLen = 0
        }

        fun writeStored(src: ByteArray, srcLen: Int) {
            var off = 0
            while (off < srcLen) {
                ensureBlock()
                val room = 65535 - blockLen
                val n = minOf(room, srcLen - off)
                src.copyInto(out, pos, off, off + n)
                pos += n
                blockLen += n
                off += n
                remainingRaw -= n
                if (blockLen == 65535) closeBlock(final = remainingRaw == 0 && off == srcLen)
            }
        }

        for (y in 0 until height) {
            row[0] = 0
            var o = 1
            var x = 0
            while (x + 4 <= width) {
                val c0 = pixels[pix++]
                val c1 = pixels[pix++]
                val c2 = pixels[pix++]
                val c3 = pixels[pix++]
                row[o++] = (c0 shr 16).toByte()
                row[o++] = (c0 shr 8).toByte()
                row[o++] = c0.toByte()
                row[o++] = (c0 ushr 24).toByte()
                row[o++] = (c1 shr 16).toByte()
                row[o++] = (c1 shr 8).toByte()
                row[o++] = c1.toByte()
                row[o++] = (c1 ushr 24).toByte()
                row[o++] = (c2 shr 16).toByte()
                row[o++] = (c2 shr 8).toByte()
                row[o++] = c2.toByte()
                row[o++] = (c2 ushr 24).toByte()
                row[o++] = (c3 shr 16).toByte()
                row[o++] = (c3 shr 8).toByte()
                row[o++] = c3.toByte()
                row[o++] = (c3 ushr 24).toByte()
                x += 4
            }
            while (x < width) {
                val c = pixels[pix++]
                row[o++] = (c shr 16).toByte()
                row[o++] = (c shr 8).toByte()
                row[o++] = c.toByte()
                row[o++] = (c ushr 24).toByte()
                x++
            }
            adler = platformAdler32(adler, row, 0, stride)
            writeStored(row, stride)
        }
        if (rawLen == 0) {
            // empty image: one empty final stored block
            ensureBlock()
            closeBlock(final = true)
        } else {
            closeBlock(final = true)
        }

        // Adler-32 trailer
        writeU32(out, pos, adler)
        pos += 4

        val actualIdat = pos - zlibStart
        writeU32(out, idatLenPos, actualIdat)
        val crc = platformCrc32(0, out, crcStart, 4 + actualIdat)
        writeU32(out, pos, crc)
        pos += 4

        pos = writeChunk(out, pos, TYPE_IEND, EMPTY, 0)
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
