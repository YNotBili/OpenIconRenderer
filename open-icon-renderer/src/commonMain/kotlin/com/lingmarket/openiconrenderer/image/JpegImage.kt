package com.lingmarket.openiconrenderer.image

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Baseline / extended-sequential JPEG (SOF0/SOF1) decoder: Huffman, 8-bit samples,
 * 1 (gray) / 3 (YCbCr) / 4 (CMYK) components, any 4:x:y sampling, restart markers.
 * Progressive (SOF2), arithmetic coding, multi-scan and 12-bit return null —
 * never a fake-color placeholder, so callers can fall back honestly.
 */
internal class JpegImage(private val d: ByteArray) {

    private class Component(val id: Int, val hv: Int, val tq: Int) {
        var w = 0
        var h = 0
        var plane: IntArray? = null
        val hs get() = (hv ushr 4) and 0xF
        val vs get() = hv and 0xF
    }

    private class Huff(counts: IntArray, private val symbols: IntArray) {
        private val firstCode = IntArray(17)
        private val maxCode = IntArray(17)
        private val valPtr = IntArray(17)

        init {
            var code = 0
            var k = 0
            for (len in 1..16) {
                valPtr[len] = k
                if (counts[len] > 0) {
                    firstCode[len] = code
                    maxCode[len] = code + counts[len] - 1
                } else {
                    maxCode[len] = -1
                }
                k += counts[len]
                code = (code + counts[len]) shl 1
            }
        }

        fun decode(bits: BitReader): Int {
            var code = 0
            for (len in 1..16) {
                code = (code shl 1) or bits.readBit()
                if (maxCode[len] >= 0 && code <= maxCode[len]) {
                    return symbols[valPtr[len] + (code - firstCode[len])]
                }
            }
            throw IllegalStateException("bad huffman code")
        }
    }

    private class BitReader(private val src: ByteArray, start: Int) {
        private var pos = start
        private var buf = 0
        private var left = 0

        fun readBit(): Int {
            if (left == 0) {
                var b = if (pos < src.size) src[pos].toInt() and 0xFF else 0
                pos++
                if (b == 0xFF) {
                    val next = if (pos < src.size) src[pos].toInt() and 0xFF else 0
                    if (next == 0x00) pos++ // stuffed byte; else marker: caller stops at EOI/RST
                }
                buf = b
                left = 8
            }
            left--
            return (buf ushr left) and 1
        }

        fun receiveExtend(size: Int): Int {
            if (size == 0) return 0
            var v = 0
            repeat(size) { v = (v shl 1) or readBit() }
            return if (v < (1 shl (size - 1))) v - (1 shl size) + 1 else v
        }

        /** Consumes a RSTnn marker (or any marker bytes) at a restart boundary. */
        fun alignToMarker() {
            left = 0
            while (pos + 1 < src.size) {
                if (src[pos].toInt() and 0xFF != 0xFF) { pos++; continue }
                val m = src[pos + 1].toInt() and 0xFF
                if (m in 0xD0..0xD7) { pos += 2; return }
                if (m == 0xFF) { pos++; continue }
                pos += 2
                return
            }
        }
    }

    private val qt = HashMap<Int, IntArray>()
    private val dcTables = HashMap<Int, Huff>()
    private val acTables = HashMap<Int, Huff>()
    private var comps: List<Component> = emptyList()
    private var scanComps: List<Pair<Component, Int>> = emptyList() // (component, td<<8|ta)
    private var width = 0
    private var height = 0
    private var restartInterval = 0
    private var adobeTransform = -1

    fun decode(): RgbaBitmap? {
        if (d.size < 4 || (d[0].toInt() and 0xFF) != 0xFF || (d[1].toInt() and 0xFF) != 0xD8) return null
        var i = 2
        var sosData = -1
        while (i + 3 < d.size) {
            if ((d[i].toInt() and 0xFF) != 0xFF) { i++; continue }
            var marker = d[i + 1].toInt() and 0xFF
            if (marker == 0xFF || marker == 0x00) { i++; continue }
            i += 2
            if (marker == 0xD9) break
            if (marker in 0xD0..0xD7 || marker in 0x01..0x07) continue
            val len = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
            val segStart = i + 2
            val segEnd = minOf(i + len, d.size)
            when (marker) {
                0xC0, 0xC1 -> if (!parseSof(segStart, segEnd)) return null
                0xC4 -> if (!parseDht(segStart, segEnd)) return null
                0xDB -> if (!parseDqt(segStart, segEnd)) return null
                0xDD -> if (segStart + 1 < segEnd) {
                    restartInterval = ((d[segStart].toInt() and 0xFF) shl 8) or (d[segStart + 1].toInt() and 0xFF)
                }
                0xEE -> { // APP14: Adobe marker (CMYK transform)
                    if (len >= 12 && d.copyOfRange(segStart, segStart + 5).decodeToString() == "Adobe") {
                        adobeTransform = d[segEnd - 1].toInt() and 0xFF
                    }
                }
                0xDA -> {
                    if (!parseSos(segStart, segEnd)) return null
                    sosData = segEnd
                }
                else -> if (marker in 0xC2..0xCF) return null // progressive / arithmetic / other SOF: unsupported
            }
            i += len
            if (sosData >= 0) break
        }
        if (sosData < 0 || width <= 0 || height <= 0 || comps.isEmpty()) return null
        return scan(sosData)
    }

    private fun parseSof(at: Int, end: Int): Boolean {
        if (at + 6 > end) return false
        if ((d[at].toInt() and 0xFF) != 8) return false
        height = ((d[at + 1].toInt() and 0xFF) shl 8) or (d[at + 2].toInt() and 0xFF)
        width = ((d[at + 3].toInt() and 0xFF) shl 8) or (d[at + 4].toInt() and 0xFF)
        val n = d[at + 5].toInt() and 0xFF
        if (n !in intArrayOf(1, 3, 4) || at + 6 + n * 3 > end) return false
        comps = (0 until n).map { c ->
            Component(d[at + 6 + c * 3].toInt() and 0xFF, d[at + 7 + c * 3].toInt() and 0xFF, d[at + 8 + c * 3].toInt() and 0xFF)
        }
        return true
    }

    private fun parseDht(at: Int, end: Int): Boolean {
        var p = at
        while (p + 17 < end) {
            val tcTd = d[p].toInt() and 0xFF
            val counts = IntArray(17)
            var total = 0
            for (l in 1..16) {
                counts[l] = d[p + l].toInt() and 0xFF
                total += counts[l]
            }
            p += 17
            if (p + total > end) return false
            val symbols = IntArray(total) { d[p + it].toInt() and 0xFF }
            p += total
            val table = Huff(counts, symbols)
            if ((tcTd ushr 4) == 0) dcTables[tcTd and 0xF] = table else acTables[tcTd and 0xF] = table
        }
        return true
    }

    private fun parseDqt(at: Int, end: Int): Boolean {
        var p = at
        while (p < end) {
            val pq = d[p].toInt() and 0xFF
            val prec = pq ushr 4
            val id = pq and 0xF
            p++
            val n = if (prec == 1) 128 else 64
            if (p + n > end) return false
            val table = IntArray(64)
            for (k in 0 until 64) {
                table[zigzag[k]] = if (prec == 1) {
                    ((d[p + k * 2].toInt() and 0xFF) shl 8) or (d[p + k * 2 + 1].toInt() and 0xFF)
                } else {
                    d[p + k].toInt() and 0xFF
                }
            }
            qt[id] = table
            p += n
        }
        return true
    }

    private fun parseSos(at: Int, end: Int): Boolean {
        val ns = d[at].toInt() and 0xFF
        if (ns != comps.size || at + 1 + ns * 2 + 3 > end) return false
        scanComps = (0 until ns).map { s ->
            val sel = comps.firstOrNull { it.id == (d[at + 1 + s * 2].toInt() and 0xFF) } ?: return false
            val tables = (d[at + 2 + s * 2].toInt() and 0xFF)
            sel to tables
        }
        val ss = d[at + 1 + ns * 2].toInt() and 0xFF
        val se = d[at + 2 + ns * 2].toInt() and 0xFF
        val ah = d[at + 3 + ns * 2].toInt() and 0xFF
        return ss == 0 && se == 63 && ah == 0
    }

    private fun scan(dataStart: Int): RgbaBitmap? {
        val hmax = comps.maxOf { it.hs }
        val vmax = comps.maxOf { it.vs }
        val mcusX = (width + hmax * 8 - 1) / (hmax * 8)
        val mcusY = (height + vmax * 8 - 1) / (vmax * 8)
        for (c in comps) {
            c.w = mcusX * c.hs * 8
            c.h = mcusY * c.vs * 8
            c.plane = IntArray(c.w * c.h)
        }
        val bits = BitReader(d, dataStart)
        val dcPred = IntArray(scanComps.size)
        var mcuIn = 0
        val block = IntArray(64)
        for (my in 0 until mcusY) {
            for (mx in 0 until mcusX) {
                if (restartInterval > 0 && mcuIn == restartInterval) {
                    bits.alignToMarker()
                    dcPred.fill(0)
                    mcuIn = 0
                }
                mcuIn++
                for ((ci, sc) in scanComps.withIndex()) {
                    val (c, tables) = sc
                    val dcT = dcTables[tables ushr 4] ?: return null
                    val acT = acTables[tables and 0xF] ?: return null
                    val quant = qt[c.tq] ?: return null
                    for (v in 0 until c.vs) {
                        for (u in 0 until c.hs) {
                            val bx = (mx * c.hs + u) * 8
                            val by = (my * c.vs + v) * 8
                            if (!decodeBlock(bits, dcT, acT, quant, dcPred, ci, block)) return null
                            writeBlock(c, bx, by, block)
                        }
                    }
                }
            }
        }
        return compose()
    }

    private fun decodeBlock(
        bits: BitReader,
        dcT: Huff,
        acT: Huff,
        quant: IntArray,
        dcPred: IntArray,
        ci: Int,
        out: IntArray,
    ): Boolean {
        out.fill(0)
        val t = try { dcT.decode(bits) } catch (e: IllegalStateException) { return false }
        if (t > 11) return false
        val diff = bits.receiveExtend(t)
        dcPred[ci] += diff
        out[0] = dcPred[ci] * quant[0]
        var k = 1
        while (k < 64) {
            val rs = try { acT.decode(bits) } catch (e: IllegalStateException) { return false }
            val run = rs ushr 4
            val size = rs and 0xF
            if (size == 0) {
                if (run == 15) { k += 16; continue } else break // EOB
            } else if (size > 10) return false
            k += run
            if (k >= 64) break
            // quant is stored in NATURAL order; k is the zigzag position.
            out[zigzag[k]] = bits.receiveExtend(size) * quant[zigzag[k]]
            k++
        }
        idct8(out)
        return true
    }

    private fun writeBlock(c: Component, bx: Int, by: Int, block: IntArray) {
        val plane = c.plane ?: return
        for (y in 0 until 8) {
            val py = by + y
            if (py >= c.h) break
            var o = py * c.w + bx
            var bo = y * 8
            for (x in 0 until 8) {
                if (bx + x < c.w) plane[o++] = block[bo]
                bo++
            }
        }
    }

    private fun compose(): RgbaBitmap {
        val px = IntArray(width * height)
        val p0 = comps[0].plane!!
        when (comps.size) {
            1 -> for (j in 0 until height) {
                for (x in 0 until width) {
                    val v = clamp(p0[j * comps[0].w + x])
                    px[j * width + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
            3 -> {
                val cb = comps[1]
                val cr = comps[2]
                val pb = cb.plane!!
                val pr = cr.plane!!
                // Planes span the MCU-padded frame, so map luma->chroma by the PADDED
                // size (comps[0].w/h), not the visible width/height — using the latter
                // stretches chroma and washes out colors.
                for (j in 0 until height) {
                    val cy = (j * cb.h / comps[0].h).coerceAtMost(cb.h - 1)
                    val rowY = j * comps[0].w
                    val rowB = cy * cb.w
                    val out = j * width
                    for (x in 0 until width) {
                        val cx = (x * cb.w / comps[0].w).coerceAtMost(cb.w - 1)
                        val c1 = pb[rowB + cx] - 128
                        val c2 = pr[rowB + cx] - 128
                        val yy = p0[rowY + x]
                        // Fixed-point YCbCr->RGB (constants over 1024: 1.402, 0.344, 0.714, 1.772).
                        px[out + x] = (0xFF shl 24) or
                            (clamp(yy + ((1436 * c2 + 512) shr 10)) shl 16) or
                            (clamp(yy - ((352 * c1 + 731 * c2 + 512) shr 10)) shl 8) or
                            clamp(yy + ((1814 * c1 + 512) shr 10))
                    }
                }
            }
            else -> { // CMYK: subtractive approximation; Adobe transform=2 means inverted components
                val pm = comps[1].plane!!
                val pc = comps[2].plane!!
                val pk = comps[3].plane!!
                val invert = adobeTransform != 2
                val w0 = comps[0].w
                val w1 = comps[1].w
                val w2 = comps[2].w
                val w3 = comps[3].w
                for (j in 0 until height) {
                    for (x in 0 until width) {
                        fun ink(p: IntArray, w: Int): Int {
                            val raw = p[j.coerceAtMost(p.size / w - 1) * w + x.coerceAtMost(w - 1)]
                            return clamp(if (invert) 255 - raw else raw)
                        }
                        val k = ink(pk, w3)
                        val r = clamp(255 - ink(p0, w0) - k)
                        val g = clamp(255 - ink(pm, w1) - k)
                        val b = clamp(255 - ink(pc, w2) - k)
                        px[j * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
        }
        return RgbaBitmap(width, height, px)
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private companion object {
        val zigzag = intArrayOf(
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
        )

        val cosTable: Array<FloatArray> = Array(8) { u ->
            FloatArray(8) { x ->
                val a = if (u == 0) 0.70710678f else 1f
                a * cos((2 * x + 1) * u * PI / 16.0).toFloat()
            }
        }

        fun idct8(b: IntArray) {
            val tmp = FloatArray(64)
            for (v in 0 until 8) {
                for (x in 0 until 8) {
                    var sum = 0f
                    for (u in 0 until 8) sum += b[v * 8 + u] * cosTable[u][x]
                    tmp[v * 8 + x] = sum
                }
            }
            for (x in 0 until 8) {
                for (v in 0 until 8) {
                    var sum = 0f
                    for (u in 0 until 8) sum += tmp[u * 8 + x] * cosTable[u][v]
                    // 1/4 = (1/2 per 1-D pass) — the separable form of the 2-D IDCT scale.
                    b[v * 8 + x] = ((sum * 0.25f).roundToInt() + 128).coerceIn(0, 255)
                }
            }
        }
    }
}
