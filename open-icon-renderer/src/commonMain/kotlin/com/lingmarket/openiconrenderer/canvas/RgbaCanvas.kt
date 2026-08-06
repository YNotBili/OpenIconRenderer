package com.lingmarket.openiconrenderer.canvas

internal class RgbaBitmap(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    /** Non-null when every pixel equals this ARGB (solid fill / create). */
    val uniformArgb: Int? = null,
) {
    fun copy(): RgbaBitmap = RgbaBitmap(width, height, pixels.copyOf(), uniformArgb)

    companion object {
        fun create(width: Int, height: Int, color: Int = 0): RgbaBitmap {
            val pixels = IntArray(width * height)
            if (color != 0) pixels.fill(color)
            return RgbaBitmap(width, height, pixels, uniformArgb = color)
        }

        /** Like [create] but not marked uniform — safe to draw into afterwards. */
        fun filled(width: Int, height: Int, color: Int = 0): RgbaBitmap {
            val pixels = IntArray(width * height)
            if (color != 0) pixels.fill(color)
            return RgbaBitmap(width, height, pixels, uniformArgb = null)
        }
    }
}

internal object RgbaCanvas {
    fun composite(base: RgbaBitmap, overlay: RgbaBitmap) {
        require(base.width == overlay.width && base.height == overlay.height)
        val solid = overlay.uniformArgb
        if (solid != null && (solid ushr 24) == 0xFF) {
            base.pixels.fill(solid)
            return
        }
        compositeRect(base, overlay, 0, 0, base.width - 1, base.height - 1)
    }

    /** Src-over [overlay] onto [base] for inclusive pixel rect (skips zero-alpha runs). */
    fun compositeRect(
        base: RgbaBitmap,
        overlay: RgbaBitmap,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        require(base.width == overlay.width && base.height == overlay.height)
        val w = base.width
        val bs = base.pixels
        val ov = overlay.pixels
        val left = x0.coerceAtLeast(0)
        val right = x1.coerceAtMost(w - 1)
        val top = y0.coerceAtLeast(0)
        val bottom = y1.coerceAtMost(base.height - 1)
        if (left > right || top > bottom) return
        for (y in top..bottom) {
            var i = y * w + left
            val rowEnd = y * w + right
            while (i <= rowEnd) {
                val s = ov[i]
                if (s == 0) {
                    i++
                    continue
                }
                val sa = (s ushr 24) and 0xFF
                if (sa == 255) {
                    bs[i] = s
                } else {
                    bs[i] = blend(bs[i], s)
                }
                i++
            }
        }
    }

    /** Multiply each pixel's straight alpha by [factor] (RGB unchanged). */
    fun scaleStraightAlpha(bitmap: RgbaBitmap, factor: Float) {
        val f = factor.coerceIn(0f, 1f)
        if (f >= 0.999f) return
        val pixels = bitmap.pixels
        val mul = (f * 255f + 0.5f).toInt().coerceIn(0, 255)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            if (a == 0) continue
            val na = (a * mul + 127) / 255
            pixels[i] = (na shl 24) or (p and 0x00FFFFFF)
        }
    }

    fun resize(source: RgbaBitmap, targetWidth: Int, targetHeight: Int): RgbaBitmap {
        if (source.width == targetWidth && source.height == targetHeight) return source
        source.uniformArgb?.let { return RgbaBitmap.create(targetWidth, targetHeight, it) }

        val out = RgbaBitmap.create(targetWidth, targetHeight)
        val sw = source.width
        val sh = source.height
        val src = source.pixels
        val dst = out.pixels

        // Fixed-point bilinear (16.16): each output pixel maps in O(1) to a 2x2 neighborhood.
        val xRatio = ((sw shl 16) / targetWidth).coerceAtLeast(1)
        val yRatio = ((sh shl 16) / targetHeight).coerceAtLeast(1)
        val maxX = sw - 1
        val maxY = sh - 1

        for (y in 0 until targetHeight) {
            val sy = y * yRatio - 32768
            val y0 = (sy ushr 16).coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val yFrac = (sy and 0xFFFF).coerceIn(0, 65535)
            val yRow0 = y0 * sw
            val yRow1 = y1 * sw
            val dstRow = y * targetWidth
            for (x in 0 until targetWidth) {
                val sx = x * xRatio - 32768
                val x0 = (sx ushr 16).coerceIn(0, maxX)
                val x1 = (x0 + 1).coerceAtMost(maxX)
                val xFrac = (sx and 0xFFFF).coerceIn(0, 65535)
                dst[dstRow + x] = lerp4Fixed(
                    src[yRow0 + x0], src[yRow0 + x1],
                    src[yRow1 + x0], src[yRow1 + x1],
                    xFrac, yFrac,
                )
            }
        }
        return RgbaBitmap(targetWidth, targetHeight, dst, uniformArgb = null)
    }

    fun fill(bitmap: RgbaBitmap, color: Int) {
        bitmap.pixels.fill(color)
    }

    fun drawBitmap(target: RgbaBitmap, source: RgbaBitmap, x: Int = 0, y: Int = 0) {
        for (sy in 0 until source.height) {
            val ty = y + sy
            if (ty !in 0 until target.height) continue
            for (sx in 0 until source.width) {
                val tx = x + sx
                if (tx !in 0 until target.width) continue
                val src = source.pixels[sy * source.width + sx]
                val dstIndex = ty * target.width + tx
                target.pixels[dstIndex] = blend(target.pixels[dstIndex], src)
            }
        }
    }

    private fun blend(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        val invSa = 255 - sa
        val outA = sa + (da * invSa + 127) / 255
        if (outA == 0) return 0
        // Premultiplied src-over, unpremultiply back to straight ARGB
        val outPR = ((src shr 16) and 0xFF) * sa + ((dst shr 16) and 0xFF) * da * invSa / 255
        val outPG = ((src shr 8) and 0xFF) * sa + ((dst shr 8) and 0xFF) * da * invSa / 255
        val outPB = (src and 0xFF) * sa + (dst and 0xFF) * da * invSa / 255
        val r = (outPR + outA / 2) / outA
        val g = (outPG + outA / 2) / outA
        val b = (outPB + outA / 2) / outA
        return (outA shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun lerp4Fixed(c00: Int, c10: Int, c01: Int, c11: Int, fx: Int, fy: Int): Int {
        val top = lerpFixed(c00, c10, fx)
        val bottom = lerpFixed(c01, c11, fx)
        return lerpFixed(top, bottom, fy)
    }

    private fun lerpFixed(a: Int, b: Int, t: Int): Int {
        // t in 0..65535
        val inv = 65536 - t
        val ar = (((a shr 16) and 0xFF) * inv + ((b shr 16) and 0xFF) * t + 32768) ushr 16
        val ag = (((a shr 8) and 0xFF) * inv + ((b shr 8) and 0xFF) * t + 32768) ushr 16
        val ab = ((a and 0xFF) * inv + (b and 0xFF) * t + 32768) ushr 16
        val aa = (((a ushr 24) and 0xFF) * inv + ((b ushr 24) and 0xFF) * t + 32768) ushr 16
        return (aa shl 24) or (ar shl 16) or (ag shl 8) or ab
    }
}

internal fun colorFromString(color: String): Int? {
    val trimmed = color.trim()
    if (!trimmed.startsWith("#")) return null
    val h = trimmed.removePrefix("#")
    return when (h.length) {
        3 -> {
            val r = h[0].toString().repeat(2).toInt(16)
            val g = h[1].toString().repeat(2).toInt(16)
            val b = h[2].toString().repeat(2).toInt(16)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        4 -> {
            val a = h[0].toString().repeat(2).toInt(16)
            val r = h[1].toString().repeat(2).toInt(16)
            val g = h[2].toString().repeat(2).toInt(16)
            val b = h[3].toString().repeat(2).toInt(16)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        6 -> {
            val r = h.substring(0, 2).toInt(16)
            val g = h.substring(2, 4).toInt(16)
            val b = h.substring(4, 6).toInt(16)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        8 -> {
            val a = h.substring(0, 2).toInt(16)
            val r = h.substring(2, 4).toInt(16)
            val g = h.substring(4, 6).toInt(16)
            val b = h.substring(6, 8).toInt(16)
            (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        else -> null
    }
}

internal fun solidColorBitmap(width: Int, height: Int, color: Int): RgbaBitmap {
    return RgbaBitmap.create(width, height, color)
}
