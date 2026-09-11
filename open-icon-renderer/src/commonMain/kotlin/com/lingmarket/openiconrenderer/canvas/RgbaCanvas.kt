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
        // Output pixel x covers source range [x*ratio, (x+1)*ratio); its sampling centre is
        // x*ratio + ratio/2, and the -32768 is the half-texel offset that puts the origin at the
        // centre of texel 0 rather than its left edge:
        //
        //     src = (x + 0.5) * sw / tw - 0.5
        //
        // Dropping the +ratio/2 term (as this used to) leaves the sample sitting on the *left edge*
        // of the destination footprint, so at any downscale ratio each output pixel reads mostly a
        // single corner texel instead of averaging the covered ones. That is what dragged edge
        // colour inwards and, combined with the straight-alpha assumptions in lerp4Fixed, surfaced
        // as a light rim on the masked icon.
        val xRatio = ((sw shl 16) / targetWidth).coerceAtLeast(1)
        val yRatio = ((sh shl 16) / targetHeight).coerceAtLeast(1)
        val xBias = xRatio / 2 - 32768
        val yBias = yRatio / 2 - 32768
        val maxX = sw - 1
        val maxY = sh - 1

        for (y in 0 until targetHeight) {
            val sy = y * yRatio + yBias
            // `shr` (arithmetic) floors negatives toward -infinity, which is what the half-texel
            // offset needs; `ushr` would read the sign bit as data and decode a negative sample as
            // 65535 before the clamp, snapping it to the last row/column.
            val y0 = (sy shr 16).coerceIn(0, maxY)
            val y1 = (y0 + 1).coerceAtMost(maxY)
            val yFrac = (sy - (y0 shl 16)).coerceIn(0, 65535)
            val yRow0 = y0 * sw
            val yRow1 = y1 * sw
            val dstRow = y * targetWidth
            for (x in 0 until targetWidth) {
                val sx = x * xRatio + xBias
                val x0 = (sx shr 16).coerceIn(0, maxX)
                val x1 = (x0 + 1).coerceAtMost(maxX)
                val xFrac = (sx - (x0 shl 16)).coerceIn(0, 65535)
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

    /**
     * Bilinear step over four straight-alpha ARGB samples.
     *
     * Channels are interpolated in premultiplied space, then divided back out by the interpolated
     * alpha. A plain per-channel lerp would mix in the RGB of fully transparent pixels, whose
     * colour is arbitrary (white for most encoders, all-zero for freshly cleared buffers); around a
     * mask rim that drags a light halo inwards and shows up as a white edge after downscaling.
     * Premultiplying makes transparent texels contribute no colour, which matches the reference
     * pipelines this renderer is compared against.
     *
     * Accumulators are Long: weights sum to 65536^2 and channels reach 255, so the intermediate
     * channel*alpha*weight product needs more than 32 bits. The alpha result is divided by the same
     * 65536^2 total weight (not shifted by 16), otherwise the interpolated alpha comes out scaled by
     * 65536 and the final clamp silently pins it to 255.
     */
    private fun lerp4Fixed(c00: Int, c10: Int, c01: Int, c11: Int, fx: Int, fy: Int): Int {
        // Fast path: all four taps identical. That is the overwhelmingly common case for flat fills
        // and for the interior of a rendered glyph, and it short-circuits all the fixed-point work.
        // Fully transparent taps must not short-circuit: their stored RGB is meaningless (encoders
        // usually write white) and callers rely on the result being a clean zero.
        if (c00 == c10 && c00 == c01 && c00 == c11) {
            return if ((c00 ushr 24) == 0) 0 else c00
        }

        val w00 = (65536L - fx) * (65536L - fy)
        val w10 = fx.toLong() * (65536L - fy)
        val w01 = (65536L - fx) * fy
        val w11 = fx.toLong() * fy

        val a00 = ((c00 ushr 24) and 0xFF).toLong() * w00
        val a10 = ((c10 ushr 24) and 0xFF).toLong() * w10
        val a01 = ((c01 ushr 24) and 0xFF).toLong() * w01
        val a11 = ((c11 ushr 24) and 0xFF).toLong() * w11
        val aSum = a00 + a10 + a01 + a11
        if (aSum == 0L) return 0
        // Total weight is 65536^2; dividing normalises the alpha, and the scaled aSum doubles as
        // the denominator that unpremultiplies the colour channels below.
        val totalWeight = w00 + w10 + w01 + w11
        val alpha = ((aSum + totalWeight / 2) / totalWeight).toInt().coerceIn(0, 255)

        // Premultiplied channel accumulation, then unpremultiply. `aSum` is the weight-scaled
        // alpha, so dividing by it is equivalent to dividing by (alpha * totalWeight) and keeps the
        // ratio exact; the three Long divisions only run for genuinely mixed taps, since identical
        // taps already returned above.
        fun channel(shift: Int): Int {
            val p00 = ((c00 shr shift) and 0xFF).toLong() * ((c00 ushr 24) and 0xFF)
            val p10 = ((c10 shr shift) and 0xFF).toLong() * ((c10 ushr 24) and 0xFF)
            val p01 = ((c01 shr shift) and 0xFF).toLong() * ((c01 ushr 24) and 0xFF)
            val p11 = ((c11 shr shift) and 0xFF).toLong() * ((c11 ushr 24) and 0xFF)
            val acc = p00 * w00 + p10 * w10 + p01 * w01 + p11 * w11
            return (acc / aSum).toInt().coerceIn(0, 255)
        }

        val r = channel(16)
        val g = channel(8)
        val b = channel(0)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
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
