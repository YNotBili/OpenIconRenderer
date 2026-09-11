package com.lingmarket.openiconrenderer.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression for the "white edge after circle crop" report.
 *
 * The renderer works on straight (non-premultiplied) ARGB. Two places used to drop the colour
 * of partially transparent texels, which let a light halo leak in around a mask rim:
 *  - softMaskPixel faded A but left RGB at full intensity, so a light background composites the
 *    rim back up to near-white;
 *  - lerp4Fixed interpolated channels straight across, mixing the RGB of fully transparent
 *    neighbours (which encoders usually store as white) into opaque pixels while downscaling.
 */
class WhiteEdgeRegressionTest {

    /** A 1x1 downscale of four samples: two opaque red, two fully transparent (white-stored). */
    @Test
    fun resizeDoesNotBleedTransparentWhiteIntoOpaque() {
        val opaqueRed = 0xFFFF0000.toInt()
        val clearWhite = 0x00FFFFFF
        val src = RgbaBitmap(
            2, 2,
            intArrayOf(opaqueRed, clearWhite, clearWhite, opaqueRed),
            uniformArgb = null,
        )
        val out = RgbaCanvas.resize(src, 1, 1)
        val c = out.pixels[0]
        val r = (c ushr 16) and 0xFF
        val g = (c ushr 8) and 0xFF
        val b = c and 0xFF

        // All four weights are equal, so alpha is (255+0+0+255)/4 = 127.5 -> 128.
        assertEquals(128, (c ushr 24) and 0xFF, "alpha should average the four samples")
        // Colour must stay red. A straight lerp would pull green/blue up toward 255 (white edge).
        assertEquals(255, r, "red channel must stay saturated")
        assertTrue(g <= 4 && b <= 4, "green/blue must not bleed in from transparent white, got g=$g b=$b")
    }

    /** Fully transparent source must not turn into a light haze. */
    @Test
    fun resizeOfFullyTransparentStaysTransparent() {
        val src = RgbaBitmap(4, 4, IntArray(16) { 0x00FFFFFF }, uniformArgb = null)
        val out = RgbaCanvas.resize(src, 1, 1)
        assertEquals(0, out.pixels[0], "fully transparent input must stay fully transparent")
    }

    /** Opaque interiors must survive a downscale untouched. */
    @Test
    fun resizePreservesOpaqueInterior() {
        val src = RgbaBitmap(8, 8, IntArray(64) { 0xFF3366CC.toInt() }, uniformArgb = null)
        val out = RgbaCanvas.resize(src, 2, 2)
        out.pixels.forEach { c ->
            assertEquals(0xFF3366CC.toInt(), c, "solid colour must round-trip exactly")
        }
    }
}
