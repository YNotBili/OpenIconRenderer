package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.vector.Affine2
import com.lingmarket.openiconrenderer.vector.FillPaint
import com.lingmarket.openiconrenderer.vector.PathCommand
import com.lingmarket.openiconrenderer.vector.VectorPath
import com.lingmarket.openiconrenderer.vector.VectorRasterizer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class VectorGroupTransformTest {
    @Test
    fun androidGroupTranslateScaleCentersTwentyFourPathInViewport108() {
        val path = squarePath(0f, 0f, 24f, 0xFFFF0000.toInt())
        val raw = VectorRasterizer.rasterize(listOf(path), 108f, 108f, 108)
        val rawC = opaqueCentroid(raw.pixels, raw.width, raw.height)
        assertTrue(rawC != null && rawC.first < 24f, "untransformed 24x24 path should sit in the corner, got $rawC")

        val xform = Affine2.androidGroup(
            translateX = 27f,
            translateY = 27f,
            scaleX = 2f,
            scaleY = 2f,
            rotation = 0f,
            pivotX = 0f,
            pivotY = 0f,
        )
        val mapped = path.copy(
            commands = xform.mapCommands(path.commands),
            fill = xform.mapFill(path.fill),
        )
        val out = VectorRasterizer.rasterize(listOf(mapped), 108f, 108f, 108)
        val c = opaqueCentroid(out.pixels, out.width, out.height)
        assertTrue(c != null, "transformed path produced no opaque pixels")
        assertTrue(abs(c!!.first - 51f) < 4f && abs(c.second - 51f) < 4f, "expected ~center (51,51), got $c")
    }

    private fun squarePath(x: Float, y: Float, size: Float, argb: Int): VectorPath = VectorPath(
        commands = listOf(
            PathCommand.MoveTo(x, y),
            PathCommand.LineTo(x + size, y),
            PathCommand.LineTo(x + size, y + size),
            PathCommand.LineTo(x, y + size),
            PathCommand.Close,
        ),
        fill = FillPaint.Solid(argb),
        strokeColor = null,
        strokeWidth = 0f,
        fillAlpha = 1f,
        strokeAlpha = 1f,
    )

    private fun opaqueCentroid(pixels: IntArray, width: Int, height: Int): Pair<Float, Float>? {
        var sx = 0.0
        var sy = 0.0
        var n = 0
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if ((pixels[row + x] ushr 24) < 128) continue
                sx += x
                sy += y
                n++
            }
        }
        if (n == 0) return null
        return (sx / n).toFloat() to (sy / n).toFloat()
    }
}
