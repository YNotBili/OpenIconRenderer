package com.lingmarket.openiconrenderer.vector

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.util.fillLinearLutSpan
import com.lingmarket.openiconrenderer.util.fillRadialLutSpan
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Scanline rasterizer inspired by Skia CPU AAA (analytic coverage), without linking Skia:
 * - Flatten curves → edges
 * - Active-edge table with fixed-point x
 * - Per pixel-row strip, pair edges into trapezoids and compute exact coverage area
 * - No supersampling; shade once per pixel × coverage (Skia-style separation)
 */
internal object VectorRasterizer {
    private const val SHIFT = 16
    private const val ONE = 1 shl SHIFT

    fun rasterize(
        paths: List<VectorPath>,
        viewportWidth: Float,
        viewportHeight: Float,
        outputSize: Int,
    ): RgbaBitmap {
        val bitmap = RgbaBitmap(outputSize, outputSize, IntArray(outputSize * outputSize), uniformArgb = null)
        rasterizeOnto(bitmap, paths, viewportWidth, viewportHeight)
        return bitmap
    }

    /** Draw [paths] onto an existing bitmap (e.g. solid adaptive background). */
    fun rasterizeOnto(
        bitmap: RgbaBitmap,
        paths: List<VectorPath>,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        val scaleX = bitmap.width / viewportWidth
        val scaleY = bitmap.height / viewportHeight
        val pxTol = when {
            bitmap.width >= 1536 -> 0.45f
            bitmap.width >= 768 -> 0.35f
            else -> 0.25f
        }
        val tol2 = pxTol * pxTol
        for (path in paths) {
            val fill = path.fill ?: continue
            val edges = buildEdges(path.commands, scaleX, scaleY, tol2)
            if (edges.isEmpty()) continue
            var yMin = edges[0].yMin
            var yMax = edges[0].yMax
            for (i in 1 until edges.size) {
                val e = edges[i]
                if (e.yMin < yMin) yMin = e.yMin
                if (e.yMax > yMax) yMax = e.yMax
            }
            if (yMax <= 0f || yMin >= bitmap.height) continue
            val sampler = GradientSampler.create(fill, scaleX, scaleY)
            fillAnalytic(bitmap, edges, sampler, path.fillAlpha, path.fillType)
        }
    }

    private fun buildEdges(
        commands: List<PathCommand>,
        scaleX: Float,
        scaleY: Float,
        tol2: Float,
    ): List<Edge> {
        val edges = ArrayList<Edge>(256)
        var x = 0f
        var y = 0f
        var startX = 0f
        var startY = 0f
        for (cmd in commands) {
            when (cmd) {
                is PathCommand.MoveTo -> {
                    x = cmd.x * scaleX
                    y = cmd.y * scaleY
                    startX = x
                    startY = y
                }
                is PathCommand.LineTo -> {
                    addEdge(edges, x, y, cmd.x * scaleX, cmd.y * scaleY)
                    x = cmd.x * scaleX
                    y = cmd.y * scaleY
                }
                is PathCommand.CubicTo -> {
                    val x1 = cmd.x1 * scaleX
                    val y1 = cmd.y1 * scaleY
                    val x2 = cmd.x2 * scaleX
                    val y2 = cmd.y2 * scaleY
                    val x3 = cmd.x * scaleX
                    val y3 = cmd.y * scaleY
                    flattenCubic(edges, x, y, x1, y1, x2, y2, x3, y3, tol2 = tol2, depth = 0)
                    x = x3
                    y = y3
                }
                PathCommand.Close -> {
                    addEdge(edges, x, y, startX, startY)
                    x = startX
                    y = startY
                }
            }
        }
        edges.sortBy { it.yMin }
        return edges
    }

    private fun addEdge(edges: MutableList<Edge>, x0: Float, y0: Float, x1: Float, y1: Float) {
        val dy = y1 - y0
        if (dy == 0f) return
        if (dy > 0f) {
            edges.add(Edge(y0, y1, x0, (x1 - x0) / dy, +1))
        } else {
            edges.add(Edge(y1, y0, x1, (x0 - x1) / -dy, -1))
        }
    }

    /** Recursive cubic flatten until control points are within sqrt(tol2) of the chord. */
    private fun flattenCubic(
        edges: MutableList<Edge>,
        x0: Float, y0: Float,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        x3: Float, y3: Float,
        tol2: Float,
        depth: Int,
    ) {
        // Distance^2 of control points from chord (x0,y0)-(x3,y3).
        val dx = x3 - x0
        val dy = y3 - y0
        val len2 = dx * dx + dy * dy
        fun dist2ToChord(px: Float, py: Float): Float {
            if (len2 < 1e-12f) {
                val ex = px - x0
                val ey = py - y0
                return ex * ex + ey * ey
            }
            var t = ((px - x0) * dx + (py - y0) * dy) / len2
            t = t.coerceIn(0f, 1f)
            val qx = x0 + t * dx
            val qy = y0 + t * dy
            val ex = px - qx
            val ey = py - qy
            return ex * ex + ey * ey
        }
        if (depth >= 16 || (dist2ToChord(x1, y1) <= tol2 && dist2ToChord(x2, y2) <= tol2)) {
            addEdge(edges, x0, y0, x3, y3)
            return
        }
        // de Casteljau split
        val x01 = (x0 + x1) * 0.5f
        val y01 = (y0 + y1) * 0.5f
        val x12 = (x1 + x2) * 0.5f
        val y12 = (y1 + y2) * 0.5f
        val x23 = (x2 + x3) * 0.5f
        val y23 = (y2 + y3) * 0.5f
        val x012 = (x01 + x12) * 0.5f
        val y012 = (y01 + y12) * 0.5f
        val x123 = (x12 + x23) * 0.5f
        val y123 = (y12 + y23) * 0.5f
        val x0123 = (x012 + x123) * 0.5f
        val y0123 = (y012 + y123) * 0.5f
        flattenCubic(edges, x0, y0, x01, y01, x012, y012, x0123, y0123, tol2, depth + 1)
        flattenCubic(edges, x0123, y0123, x123, y123, x23, y23, x3, y3, tol2, depth + 1)
    }

    private fun fillAnalytic(
        bitmap: RgbaBitmap,
        edges: List<Edge>,
        sampler: GradientSampler,
        fillAlpha: Float,
        fillType: FillType,
    ) {
        val w = bitmap.width
        val h = bitmap.height
        if (edges.isEmpty()) return

        val yMin = max(0, floor(edges.first().yMin).toInt())
        var yMaxEdge = edges[0].yMax
        for (i in 1 until edges.size) yMaxEdge = max(yMaxEdge, edges[i].yMax)
        val yMax = min(h - 1, ceil(yMaxEdge).toInt() - 1)
        if (yMax < yMin) return

        val active = ArrayList<ActiveEdge>(edges.size)
        var edgeIdx = 0
        val traps = ArrayList<Trap>(16)
        val cover = FloatArray(w)
        val cuts = ArrayList<Float>(16)
        val splits = ArrayList<Float>(16)

        val solid = sampler.solidArgbOrNull()
        val solidA = if (solid != null) {
            ((((solid ushr 24) and 0xFF) / 255f) * fillAlpha).coerceIn(0f, 1f)
        } else {
            -1f
        }
        val opaqueSolid = solid != null && solidA >= 0.999f
        val solidSrc = if (opaqueSolid) {
            (255 shl 24) or (solid and 0x00FFFFFF)
        } else {
            0
        }
        val opaquePaint = fillAlpha >= 0.999f && !sampler.isSemiTransparentPaint()

        for (y in yMin..yMax) {
            val yTop = y.toFloat()
            val yBot = yTop + 1f

            // Drop edges that ended at/before this pixel row.
            compactActive(active, yTop)

            // Split the 1px strip at mid-pixel edge starts/ends so we never evaluate
            // an edge past its own [yMin,yMax] (that extrapolation paints 1px scars).
            cuts.clear()
            for (ae in active) {
                if (ae.yMax > yTop && ae.yMax < yBot) cuts.add(ae.yMax)
            }
            var peek = edgeIdx
            while (peek < edges.size && edges[peek].yMin < yBot) {
                val e = edges[peek++]
                if (e.yMin > yTop) cuts.add(e.yMin)
                if (e.yMax > yTop && e.yMax < yBot) cuts.add(e.yMax)
            }
            cuts.sort()
            splits.clear()
            for (c in cuts) {
                if (splits.isEmpty() || abs(c - splits.last()) > 1e-5f) splits.add(c)
            }
            splits.add(yBot)

            // Fast path: no mid-pixel edge events → unit-height traps, interior fill + edge AA.
            if (splits.size == 1) {
                while (edgeIdx < edges.size && edges[edgeIdx].yMin <= yTop) {
                    val e = edges[edgeIdx++]
                    if (e.yMax > yTop) {
                        val xAt = e.x0 + (yTop - e.yMin) * e.dxdy
                        val xFp = (xAt * ONE).toInt()
                        val dxFp = (e.dxdy * ONE).toInt()
                        insertActiveSorted(active, ActiveEdge(e.yMax, xFp, dxFp, e.wind, e.yMin, e.x0, e.dxdy))
                    }
                }
                compactActive(active, yTop)
                if (active.isEmpty()) continue
                sortActiveByX(active, yTop)
                buildTraps(active, fillType, traps, yTop, yBot)
                paintTrapsFast(
                    bitmap, traps, y, w, sampler, solid, fillAlpha, opaqueSolid, solidSrc, opaquePaint,
                )
                for (ae in active) ae.xFp += ae.dxFp
                continue
            }

            cover.fill(0f)
            var ya = yTop
            var anyCover = false
            for (si in 0 until splits.size) {
                val yb = splits[si]
                val hgt = yb - ya
                if (hgt <= 1e-5f) {
                    ya = yb
                    continue
                }

                while (edgeIdx < edges.size && edges[edgeIdx].yMin <= ya) {
                    val e = edges[edgeIdx++]
                    if (e.yMax > ya) {
                        val xAt = e.x0 + (ya - e.yMin) * e.dxdy
                        val xFp = (xAt * ONE).toInt()
                        val dxFp = (e.dxdy * ONE).toInt()
                        insertActiveSorted(active, ActiveEdge(e.yMax, xFp, dxFp, e.wind, e.yMin, e.x0, e.dxdy))
                    }
                }
                compactActive(active, ya)
                if (active.isEmpty()) {
                    ya = yb
                    continue
                }

                sortActiveByX(active, ya)
                buildTraps(active, fillType, traps, ya, yb)
                for (t in traps) {
                    val edgeLo = max(0, floor(min(t.x0t, t.x0b)).toInt())
                    val edgeHi = min(w - 1, ceil(max(t.x1t, t.x1b)).toInt() - 1)
                    if (edgeLo > edgeHi) continue
                    for (x in edgeLo..edgeHi) {
                        val c = trapPixelCoverage(x.toFloat(), t.x0t, t.x1t, t.x0b, t.x1b)
                        if (c > 1e-6f) {
                            cover[x] += c * hgt
                            anyCover = true
                        }
                    }
                }
                ya = yb
            }

            if (!anyCover) {
                for (ae in active) ae.xFp += ae.dxFp
                continue
            }

            val row = y * w
            val scanY = y + 0.5f
            val pixels = bitmap.pixels
            var x = 0
            while (x < w) {
                val c0 = cover[x]
                if (c0 <= 1e-4f) {
                    x++
                    continue
                }
                if (c0 >= 1f - 1e-3f) {
                    if (opaqueSolid) {
                        var x1 = x + 1
                        while (x1 < w && cover[x1] >= 1f - 1e-3f) x1++
                        pixels.fill(solidSrc, row + x, row + x1)
                        x = x1
                        continue
                    }
                    if (opaquePaint) {
                        var x1 = x + 1
                        while (x1 < w && cover[x1] >= 1f - 1e-3f) x1++
                        sampler.fillOpaqueSpan(pixels, row, x, x1 - 1, scanY)
                        x = x1
                        continue
                    }
                }
                paintCoveragePixel(
                    bitmap, row + x, x, scanY, c0.coerceIn(0f, 1f),
                    sampler, solid, fillAlpha, opaqueSolid, solidSrc,
                )
                x++
            }

            for (ae in active) ae.xFp += ae.dxFp
        }
    }

    /** Unit-height traps: opaque interior spans + analytic AA only on edge bands. */
    private fun paintTrapsFast(
        bitmap: RgbaBitmap,
        traps: ArrayList<Trap>,
        y: Int,
        w: Int,
        sampler: GradientSampler,
        solid: Int?,
        fillAlpha: Float,
        opaqueSolid: Boolean,
        solidSrc: Int,
        opaquePaint: Boolean,
    ) {
        val row = y * w
        val scanY = y + 0.5f
        val pixels = bitmap.pixels
        for (t in traps) {
            val fullLo = max(0, ceil(max(t.x0t, t.x0b)).toInt())
            val fullHi = min(w - 1, floor(min(t.x1t, t.x1b)).toInt() - 1)
            val edgeLo = max(0, floor(min(t.x0t, t.x0b)).toInt())
            val edgeHi = min(w - 1, ceil(max(t.x1t, t.x1b)).toInt() - 1)
            if (fullLo <= fullHi) {
                when {
                    opaqueSolid -> pixels.fill(solidSrc, row + fullLo, row + fullHi + 1)
                    opaquePaint -> sampler.fillOpaqueSpan(pixels, row, fullLo, fullHi, scanY)
                    else -> {
                        for (x in fullLo..fullHi) {
                            val base = if (solid != null) solid else sampler.colorAt(x + 0.5f, scanY)
                            val baseA = ((base ushr 24) and 0xFF) / 255f * fillAlpha
                            if (baseA <= 1e-4f) continue
                            val straightA = (baseA * 255f + 0.5f).toInt().coerceIn(0, 255)
                            val src = (straightA shl 24) or (base and 0x00FFFFFF)
                            pixels[row + x] = srcOverFast(pixels[row + x], src)
                        }
                    }
                }
                for (x in edgeLo until fullLo) {
                    val c = trapPixelCoverage(x.toFloat(), t.x0t, t.x1t, t.x0b, t.x1b)
                    paintCoveragePixel(
                        bitmap, row + x, x, scanY, c,
                        sampler, solid, fillAlpha, opaqueSolid, solidSrc,
                    )
                }
                for (x in (fullHi + 1)..edgeHi) {
                    val c = trapPixelCoverage(x.toFloat(), t.x0t, t.x1t, t.x0b, t.x1b)
                    paintCoveragePixel(
                        bitmap, row + x, x, scanY, c,
                        sampler, solid, fillAlpha, opaqueSolid, solidSrc,
                    )
                }
            } else if (edgeLo <= edgeHi) {
                for (x in edgeLo..edgeHi) {
                    val c = trapPixelCoverage(x.toFloat(), t.x0t, t.x1t, t.x0b, t.x1b)
                    paintCoveragePixel(
                        bitmap, row + x, x, scanY, c,
                        sampler, solid, fillAlpha, opaqueSolid, solidSrc,
                    )
                }
            }
        }
    }

    /** Insertion sort — active lists are typically small; avoids sortBy key alloc. */
    private fun sortActiveByX(active: ArrayList<ActiveEdge>, y: Float) {
        val n = active.size
        if (n < 2) return
        for (i in 1 until n) {
            val key = active[i]
            val kx = edgeXAt(key, y)
            var j = i - 1
            while (j >= 0 && edgeXAt(active[j], y) > kx) {
                active[j + 1] = active[j]
                j--
            }
            active[j + 1] = key
        }
    }

    private fun paintCoveragePixel(
        bitmap: RgbaBitmap,
        idx: Int,
        x: Int,
        scanY: Float,
        cover: Float,
        sampler: GradientSampler,
        solid: Int?,
        fillAlpha: Float,
        opaqueSolid: Boolean,
        solidSrc: Int,
    ) {
        if (cover <= 1e-4f) return
        if (opaqueSolid) {
            if (cover >= 1f - 1e-3f) {
                bitmap.pixels[idx] = solidSrc
            } else {
                val a = (cover * 255f + 0.5f).toInt().coerceIn(0, 255)
                val src = (a shl 24) or (solidSrc and 0x00FFFFFF)
                bitmap.pixels[idx] = srcOverLinear(bitmap.pixels[idx], src)
            }
            return
        }
        val base = if (solid != null) solid else sampler.colorAt(x + 0.5f, scanY)
        val srcOpaque = ((base ushr 24) and 0xFF) / 255f * fillAlpha >= 0.99f
        val baseA = ((base ushr 24) and 0xFF) / 255f * fillAlpha * cover
        if (baseA <= 1e-4f) return
        val straightA = (baseA * 255f + 0.5f).toInt().coerceIn(0, 255)
        val src = (straightA shl 24) or (base and 0x00FFFFFF)
        val dst = bitmap.pixels[idx]
        val dstA = (dst ushr 24) and 0xFF
        // Opaque-on-opaque geometric AA in sRGB (Android-like). Avoids red fringes from
        // linear-light mixes of orange/yellow with purple at path joins.
        if (cover < 1f - 1e-3f && dstA >= 250 && srcOpaque) {
            val inv = 1f - cover
            val sr = (base shr 16) and 0xFF
            val sg = (base shr 8) and 0xFF
            val sb = base and 0xFF
            val dr = (dst shr 16) and 0xFF
            val dg = (dst shr 8) and 0xFF
            val db = dst and 0xFF
            val r = (sr * cover + dr * inv + 0.5f).toInt().coerceIn(0, 255)
            val g = (sg * cover + dg * inv + 0.5f).toInt().coerceIn(0, 255)
            val b = (sb * cover + db * inv + 0.5f).toInt().coerceIn(0, 255)
            bitmap.pixels[idx] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            return
        }
        bitmap.pixels[idx] = if (cover < 1f - 1e-3f && dstA < 8) {
            srcOverLinear(dst, src)
        } else {
            srcOverFast(dst, src)
        }
    }

    /**
     * Exact area of intersection between unit pixel [px,px+1]×[0,1] and trapezoid
     * with top [x0t,x1t] and bottom [x0b,x1b] (Skia AAA strip idea, simplified).
     */
    private fun trapPixelCoverage(px: Float, x0t: Float, x1t: Float, x0b: Float, x1b: Float): Float {
        val leftT = min(x0t, x1t)
        val rightT = max(x0t, x1t)
        val leftB = min(x0b, x1b)
        val rightB = max(x0b, x1b)
        val minL = min(leftT, leftB)
        val maxR = max(rightT, rightB)
        if (maxR <= px || minL >= px + 1f) return 0f

        // Integrate coverage along y∈[0,1]: width = clamp(right(y),px+1) - clamp(left(y),px)
        // left(y) = lerp(leftT, leftB, y), right(y) = lerp(rightT, rightB, y)
        // Piecewise-linear → sample 2-segment exact via average of endpoint clamped widths
        // plus correction for linear edges (trapezoid area formula).
        val l0 = leftT
        val l1 = leftB
        val r0 = rightT
        val r1 = rightB
        return segmentStripArea(px, px + 1f, l0, r0, l1, r1).coerceIn(0f, 1f)
    }

    /** Area of strip [0,1]×[xa,xb] clipped to left/right lines from (l0,r0) to (l1,r1). */
    private fun segmentStripArea(xa: Float, xb: Float, l0: Float, r0: Float, l1: Float, r1: Float): Float {
        // Exact area under linear left/right clipped to [xa,xb]:
        // Use the formula: ∫ max(0, min(r(y),xb) - max(l(y),xa)) dy
        // Evaluate with 1-piece analytic by clipping each edge against the pixel column.
        val area = clippedTrapArea(xa, xb, l0, r0, l1, r1)
        return area
    }

    private fun clippedTrapArea(xa: Float, xb: Float, l0: Float, r0: Float, l1: Float, r1: Float): Float {
        // Sample 4-point Gauss-like / Simpson: exact for bilinear when unclipped;
        // for clipped linear edges, use endpoints + mid (Simpson).
        fun widthAt(t: Float): Float {
            val l = l0 + (l1 - l0) * t
            val r = r0 + (r1 - r0) * t
            val lo = max(l, xa)
            val hi = min(r, xb)
            return if (hi > lo) hi - lo else 0f
        }
        val w0 = widthAt(0f)
        val w1 = widthAt(0.5f)
        val w2 = widthAt(1f)
        // Simpson's rule exact for quadratics; linear width → exact.
        return (w0 + 4f * w1 + w2) / 6f
    }

    private fun edgeXAt(ae: ActiveEdge, y: Float): Float {
        // Within a sub-strip edges are live on [ya,yb]; evaluate the infinite line.
        return ae.xAtMin + (y - ae.yMin) * ae.dxdy
    }

    private fun buildTraps(
        active: List<ActiveEdge>,
        fillType: FillType,
        out: ArrayList<Trap>,
        yTop: Float,
        yBot: Float,
    ) {
        out.clear()
        when (fillType) {
            FillType.EVEN_ODD -> {
                var i = 0
                while (i + 1 < active.size) {
                    val a = active[i]
                    val b = active[i + 1]
                    out.add(
                        Trap(
                            x0t = edgeXAt(a, yTop),
                            x1t = edgeXAt(b, yTop),
                            x0b = edgeXAt(a, yBot),
                            x1b = edgeXAt(b, yBot),
                        ),
                    )
                    i += 2
                }
            }
            FillType.NON_ZERO -> {
                var wind = 0
                var start: ActiveEdge? = null
                for (ae in active) {
                    val prev = wind
                    wind += ae.wind
                    if (prev == 0 && wind != 0) {
                        start = ae
                    } else if (prev != 0 && wind == 0) {
                        val s = start
                        if (s != null) {
                            out.add(
                                Trap(
                                    x0t = edgeXAt(s, yTop),
                                    x1t = edgeXAt(ae, yTop),
                                    x0b = edgeXAt(s, yBot),
                                    x1b = edgeXAt(ae, yBot),
                                ),
                            )
                        }
                        start = null
                    }
                }
            }
        }
    }

    private fun insertActiveSorted(active: ArrayList<ActiveEdge>, ae: ActiveEdge) {
        var lo = 0
        var hi = active.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (active[mid].xFp <= ae.xFp) lo = mid + 1 else hi = mid
        }
        active.add(lo, ae)
    }

    private fun compactActive(active: ArrayList<ActiveEdge>, yTop: Float) {
        var write = 0
        for (read in 0 until active.size) {
            val ae = active[read]
            if (ae.yMax > yTop) {
                if (write != read) active[write] = ae
                write++
            }
        }
        while (active.size > write) active.removeAt(active.lastIndex)
    }

    private fun srcOverFast(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        if (da == 0) return src
        val invSa = 255 - sa
        val outA = sa + (da * invSa + 127) / 255
        if (outA == 0) return 0
        val outPR = ((src shr 16) and 0xFF) * sa + ((dst shr 16) and 0xFF) * da * invSa / 255
        val outPG = ((src shr 8) and 0xFF) * sa + ((dst shr 8) and 0xFF) * da * invSa / 255
        val outPB = (src and 0xFF) * sa + (dst and 0xFF) * da * invSa / 255
        val r = (outPR + outA / 2) / outA
        val g = (outPG + outA / 2) / outA
        val b = (outPB + outA / 2) / outA
        return (outA shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }

    private fun srcOverLinear(dst: Int, src: Int): Int {
        val sa = (src ushr 24) and 0xFF
        if (sa == 0) return dst
        if (sa == 255) return src
        val da = (dst ushr 24) and 0xFF
        if (da == 0) return src
        val invSa = 1f - sa / 255f
        val outA = (sa / 255f + da / 255f * invSa).coerceIn(0f, 1f)
        if (outA <= 1e-4f) return 0
        fun toLin(c: Int): Float {
            val x = c / 255f
            return x * x
        }
        fun toSrgb(c: Float): Int = (sqrt(c.coerceIn(0f, 1f)) * 255f + 0.5f).toInt().coerceIn(0, 255)
        val fs = sa / 255f
        val fd = da / 255f * invSa
        val outPR = toLin((src shr 16) and 0xFF) * fs + toLin((dst shr 16) and 0xFF) * fd
        val outPG = toLin((src shr 8) and 0xFF) * fs + toLin((dst shr 8) and 0xFF) * fd
        val outPB = toLin(src and 0xFF) * fs + toLin(dst and 0xFF) * fd
        val r = toSrgb(outPR / outA)
        val g = toSrgb(outPG / outA)
        val b = toSrgb(outPB / outA)
        val a = (outA * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val INV_ONE = 1f / ONE

    private class Edge(
        val yMin: Float,
        val yMax: Float,
        val x0: Float,
        val dxdy: Float,
        val wind: Int,
    )

    private class ActiveEdge(
        val yMax: Float,
        var xFp: Int,
        val dxFp: Int,
        val wind: Int,
        val yMin: Float,
        val xAtMin: Float,
        val dxdy: Float,
    )

    private data class Trap(val x0t: Float, val x1t: Float, val x0b: Float, val x1b: Float)

    private sealed class GradientSampler {
        abstract fun colorAt(x: Float, y: Float): Int
        open fun solidArgbOrNull(): Int? = null
        open fun isSemiTransparentPaint(): Boolean = false
        /** Direct-write opaque interior span (no blend). */
        open fun fillOpaqueSpan(pixels: IntArray, row: Int, x0: Int, x1: Int, y: Float) {
            for (x in x0..x1) pixels[row + x] = colorAt(x + 0.5f, y)
        }

        private class Solid(val argb: Int) : GradientSampler() {
            override fun colorAt(x: Float, y: Float): Int = argb
            override fun solidArgbOrNull(): Int = argb
            override fun isSemiTransparentPaint(): Boolean = ((argb ushr 24) and 0xFF) < 255
            override fun fillOpaqueSpan(pixels: IntArray, row: Int, x0: Int, x1: Int, y: Float) {
                pixels.fill(argb, row + x0, row + x1 + 1)
            }
        }

        private class Linear(
            val x0: Float,
            val y0: Float,
            val dx: Float,
            val dy: Float,
            val invLen2: Float,
            val lut: IntArray,
            private val semi: Boolean,
        ) : GradientSampler() {
            override fun colorAt(x: Float, y: Float): Int {
                val t = if (invLen2 <= 0f) 0f else ((x - x0) * dx + (y - y0) * dy) * invLen2
                return lut[(t.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)]
            }
            override fun isSemiTransparentPaint(): Boolean = semi
            override fun fillOpaqueSpan(pixels: IntArray, row: Int, x0: Int, x1: Int, y: Float) {
                if (invLen2 <= 0f) {
                    val c = lut[0]
                    pixels.fill(c, row + x0, row + x1 + 1)
                    return
                }
                val t0 = ((x0 + 0.5f - this.x0) * dx + (y - y0) * dy) * invLen2
                val dt = dx * invLen2
                fillLinearLutSpan(pixels, row + x0, x1 - x0 + 1, lut, t0, dt)
            }
        }

        private class Radial(
            val cx: Float,
            val cy: Float,
            val invR: Float,
            val lut: IntArray,
            private val semi: Boolean,
        ) : GradientSampler() {
            override fun colorAt(x: Float, y: Float): Int {
                val t = if (invR <= 0f) 0f else sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy)) * invR
                return lut[(t.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)]
            }
            override fun isSemiTransparentPaint(): Boolean = semi
            override fun fillOpaqueSpan(pixels: IntArray, row: Int, x0: Int, x1: Int, y: Float) {
                fillRadialLutSpan(
                    pixels, row + x0, x1 - x0 + 1, lut,
                    x0Center = x0 + 0.5f, y = y, cx = cx, cy = cy, invR = invR,
                )
            }
        }

        companion object {
            fun create(fill: FillPaint, scaleX: Float, scaleY: Float): GradientSampler =
                when (fill) {
                    is FillPaint.Solid -> Solid(fill.argb)
                    is FillPaint.LinearGradient -> {
                        val x0 = fill.x0 * scaleX
                        val y0 = fill.y0 * scaleY
                        val x1 = fill.x1 * scaleX
                        val y1 = fill.y1 * scaleY
                        val dx = x1 - x0
                        val dy = y1 - y0
                        val len2 = dx * dx + dy * dy
                        Linear(x0, y0, dx, dy, if (len2 <= 1e-8f) 0f else 1f / len2, buildLut(fill.stops), stopsSemi(fill.stops))
                    }
                    is FillPaint.RadialGradient -> {
                        val cx = fill.cx * scaleX
                        val cy = fill.cy * scaleY
                        val r = fill.radius * ((scaleX + scaleY) * 0.5f)
                        Radial(cx, cy, if (r <= 1e-8f) 0f else 1f / r, buildLut(fill.stops), stopsSemi(fill.stops))
                    }
                }

            private fun buildLut(stops: List<GradientStop>): IntArray {
                val lut = IntArray(256)
                for (i in 0 until 256) lut[i] = sampleGradient(stops, i / 255f)
                return lut
            }

            private fun stopsSemi(stops: List<GradientStop>): Boolean =
                stops.any { ((it.argb ushr 24) and 0xFF) < 255 }
        }
    }
}
