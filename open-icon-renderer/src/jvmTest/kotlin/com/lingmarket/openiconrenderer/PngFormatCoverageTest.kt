package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.png.PngDecoder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * PNG format coverage: palette (colorType 3) at 2/4/8-bit, sub-byte grayscale,
 * 16-bit channels and tRNS. These all used to decode to `null` in production
 * ("produced no logo" icon failures) while the decoder only accepted {0,2,6}@8bit.
 */
class PngFormatCoverageTest {

    private fun Int.toBeBytes(): ByteArray =
        byteArrayOf((this ushr 24).toByte(), (this ushr 16).toByte(), (this ushr 8).toByte(), this.toByte())

    private fun chunk(type: String, body: ByteArray): ByteArray {
        val out = ByteArray(12 + body.size)
        body.size.toBeBytes().copyInto(out, 0)
        type.encodeToByteArray().copyInto(out, 4)
        body.copyInto(out, 8)
        val crc = CRC32()
        crc.update(out, 4, 4 + body.size)
        crc.value.toInt().toBeBytes().copyInto(out, 8 + body.size)
        return out
    }

    /** Builds a filter-0 PNG; [values] returns raw samples (0..2^bitDepth-1) per channel. */
    private fun buildPngRaw(
        width: Int,
        height: Int,
        colorType: Int,
        bitDepth: Int,
        spp: Int,
        palette: ByteArray? = null,
        trns: ByteArray? = null,
        values: (x: Int, y: Int, c: Int) -> Int,
    ): ByteArray {
        val ihdr = ByteArray(13)
        width.toBeBytes().copyInto(ihdr, 0)
        height.toBeBytes().copyInto(ihdr, 4)
        ihdr[8] = bitDepth.toByte()
        ihdr[9] = colorType.toByte()
        val rowBytes = (width * spp * bitDepth + 7) / 8
        val raw = ByteArray(height * (rowBytes + 1))
        for (y in 0 until height) {
            raw[y * (rowBytes + 1)] = 0
            var bit = 0
            for (x in 0 until width) {
                for (c in 0 until spp) {
                    val v = values(x, y, c)
                    for (b in bitDepth - 1 downTo 0) {
                        if (((v ushr b) and 1) == 1) {
                            val idx = y * (rowBytes + 1) + 1 + (bit ushr 3)
                            raw[idx] = (raw[idx].toInt() or (1 shl (7 - (bit and 7)))).toByte()
                        }
                        bit++
                    }
                }
            }
        }
        val z = ByteArrayOutputStream()
        val def = Deflater() // emits standard zlib stream (2-byte header + adler), like PNG does
        def.setInput(raw)
        def.finish()
        val buf = ByteArray(512)
        while (!def.finished()) z.write(buf, 0, def.deflate(buf))
        def.end()
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr) +
            (palette?.let { chunk("PLTE", it) } ?: ByteArray(0)) +
            (trns?.let { chunk("tRNS", it) } ?: ByteArray(0)) +
            chunk("IDAT", z.toByteArray()) +
            chunk("IEND", ByteArray(0))
    }

    private fun rgbOf(p: Int) = Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    private fun alphaOf(p: Int) = (p ushr 24) and 0xFF

    @Test
    fun palette8WithTrns() {
        val pal = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80, 90)
        val png = buildPngRaw(width = 2, height = 2, colorType = 3, bitDepth = 8, spp = 1, palette = pal, trns = byteArrayOf(0)) { x, y, _ ->
            x + y * 2
        }
        val bmp = assertNotNull(PngDecoder.decode(png), "palette8 decode")
        assertEquals(0, alphaOf(bmp.pixels[0]), "idx0 transparent via tRNS")
        assertEquals(255, alphaOf(bmp.pixels[1]), "idx1 opaque (no tRNS entry)")
        assertEquals(Triple(40, 50, 60), rgbOf(bmp.pixels[1]))
        assertEquals(Triple(70, 80, 90), rgbOf(bmp.pixels[2]))
    }

    @Test
    fun palette4BitPacking() {
        val pal = ByteArray(16 * 3) { (it * 7).toByte() }
        val png = buildPngRaw(width = 4, height = 1, colorType = 3, bitDepth = 4, spp = 1, palette = pal) { x, _, _ -> 15 - x }
        val bmp = assertNotNull(PngDecoder.decode(png), "palette4 decode")
        for (x in 0 until 4) {
            val o = (15 - x) * 3
            val exact = Triple(pal[o].toInt() and 0xFF, pal[o + 1].toInt() and 0xFF, pal[o + 2].toInt() and 0xFF)
            assertEquals(exact, rgbOf(bmp.pixels[x]), "pixel $x")
        }
    }

    @Test
    fun palette2BitPacking() {
        val pal = ByteArray(4 * 3) { (it * 60).toByte() }
        val png = buildPngRaw(width = 4, height = 1, colorType = 3, bitDepth = 2, spp = 1, palette = pal) { x, _, _ -> x }
        val bmp = assertNotNull(PngDecoder.decode(png), "palette2 decode")
        for (x in 0 until 4) {
            val o = x * 3
            assertEquals(
                Triple(pal[o].toInt() and 0xFF, pal[o + 1].toInt() and 0xFF, pal[o + 2].toInt() and 0xFF),
                rgbOf(bmp.pixels[x]), "pixel $x",
            )
        }
    }

    @Test
    fun gray2BitRamp() {
        val png = buildPngRaw(width = 4, height = 1, colorType = 0, bitDepth = 2, spp = 1) { x, _, _ -> x }
        val bmp = assertNotNull(PngDecoder.decode(png), "gray2 decode")
        val expect = listOf(0, 255 / 3, 2 * 255 / 3, 255)
        for (x in 0 until 4) {
            val (r, g, b) = rgbOf(bmp.pixels[x])
            assertEquals(expect[x], g, "gray ramp $x")
            assertEquals(expect[x], r); assertEquals(expect[x], b)
            assertEquals(255, alphaOf(bmp.pixels[x]))
        }
    }

    @Test
    fun rgba16TakesHighByte() {
        val png = buildPngRaw(width = 1, height = 1, colorType = 6, bitDepth = 16, spp = 4) { _, _, c ->
            intArrayOf(0xABCD, 0x1234, 0x5678, 0xF000)[c]
        }
        val bmp = assertNotNull(PngDecoder.decode(png), "rgba16 decode")
        assertEquals(Triple(0xAB, 0x12, 0x56), rgbOf(bmp.pixels[0]))
        assertEquals(0xF0, alphaOf(bmp.pixels[0]))
    }

    @Test
    fun rgb8Baseline() {
        val png = buildPngRaw(width = 1, height = 1, colorType = 2, bitDepth = 8, spp = 3) { _, _, c -> intArrayOf(1, 2, 3)[c] }
        val bmp = assertNotNull(PngDecoder.decode(png), "rgb8 decode")
        assertEquals(Triple(1, 2, 3), rgbOf(bmp.pixels[0]))
        assertEquals(255, alphaOf(bmp.pixels[0]))
    }
}
