package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.image.JpegDecoder
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Baseline JPEG decode coverage (prod icons ship JPEGs with .png names). */
class JpegDecodeTest {

    private fun jpegOf(type: Int, writer: (Graphics2D, Int, Int) -> Unit, w: Int = 40, h: Int = 24): ByteArray {
        val img = BufferedImage(w, h, type)
        writer(img.createGraphics(), w, h)
        val out = ByteArrayOutputStream()
        assertTrue(ImageIO.write(img, "jpg", out), "jpg writer available")
        return out.toByteArray()
    }

    private fun colorAt(bmp: com.lingmarket.openiconrenderer.canvas.RgbaBitmap, x: Int, y: Int): Triple<Int, Int, Int> {
        val p = bmp.pixels[y * bmp.width + x]
        return Triple((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
    }

    private fun close(a: Int, b: Int, tol: Int = 12) = kotlin.math.abs(a - b) <= tol

    @Test
    fun ycbcr420Roundtrip() {
        val bytes = jpegOf(BufferedImage.TYPE_INT_RGB, { g, w, h ->
            g.color = Color(220, 30, 40); g.fillRect(0, 0, w, h / 2)
            g.color = Color(20, 60, 200); g.fillRect(0, h / 2, w, h / 2)
        })
        val bmp = assertNotNull(JpegDecoder.decode(bytes), "decode 4:2:0 rgb jpeg")
        assertEquals(40, bmp.width)
        assertEquals(24, bmp.height)
        val (r1, g1, b1) = colorAt(bmp, 20, 5)
        println("DBG top(20,5)=($r1,$g1,$b1) bottom(20,19)=${colorAt(bmp, 20, 19)}")
        val jpeg = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
        println("DBG ImageIO says top=${java.awt.Color(jpeg.getRGB(20, 5))} rgb=${jpeg.getRGB(20,5).toString(16)}")
        assertTrue(close(r1, 220) && close(g1, 30) && close(b1, 40), "top red: $r1,$g1,$b1")
        val (r2, g2, b2) = colorAt(bmp, 20, 19)
        assertTrue(close(r2, 20) && close(g2, 60) && close(b2, 200), "bottom blue: $r2,$g2,$b2")
    }

    @Test
    fun grayscaleRoundtrip() {
        val bytes = jpegOf(BufferedImage.TYPE_BYTE_GRAY, { g, w, h ->
            g.color = Color(200, 200, 200); g.fillRect(0, 0, w, h)
        })
        val bmp = assertNotNull(JpegDecoder.decode(bytes), "decode gray jpeg")
        val (r, gr, b) = colorAt(bmp, 5, 5)
        assertTrue(close(r, 200) && close(gr, 200) && close(b, 200), "gray $r,$gr,$b")
    }

    @Test
    fun oddDimensionsNotMultipleOf8() {
        val bytes = jpegOf(BufferedImage.TYPE_INT_RGB, { g, w, h ->
            g.color = Color(10, 20, 30); g.fillRect(0, 0, w, h)
        }, w = 21, h = 13)
        val bmp = assertNotNull(JpegDecoder.decode(bytes))
        assertEquals(21, bmp.width)
        assertEquals(13, bmp.height)
        val (r, gr, b) = colorAt(bmp, 20, 12)
        assertTrue(close(r, 10) && close(gr, 20) && close(b, 30))
    }

    @Test
    fun progressiveRejectedNotFakeColor() {
        // SOF2 must return null (honest failure), never a gray placeholder.
        val fake = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC2.toByte(), 0, 2, 0, 0)
        assertEquals(null, JpegDecoder.decode(fake))
    }
}
