package com.lingmarket.openiconrenderer.vector

import com.lingmarket.openiconrenderer.canvas.colorFromString
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

internal sealed class FillPaint {
    data class Solid(val argb: Int) : FillPaint()
    data class LinearGradient(
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
        val stops: List<GradientStop>,
    ) : FillPaint()
    data class RadialGradient(
        val cx: Float,
        val cy: Float,
        val radius: Float,
        val stops: List<GradientStop>,
    ) : FillPaint()
}

internal data class GradientStop(val offset: Float, val argb: Int)

internal enum class FillType {
    NON_ZERO,
    EVEN_ODD,
}

internal data class VectorPath(
    val commands: List<PathCommand>,
    val fill: FillPaint?,
    val strokeColor: Int?,
    val strokeWidth: Float,
    val fillAlpha: Float,
    val strokeAlpha: Float,
    val fillType: FillType = FillType.NON_ZERO,
)

internal sealed interface PathCommand {
    data class MoveTo(val x: Float, val y: Float) : PathCommand
    data class LineTo(val x: Float, val y: Float) : PathCommand
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathCommand
    object Close : PathCommand
}

internal object PathDataParser {
    fun parse(pathData: String): List<PathCommand> {
        val tokens = TOKEN_REGEX.findAll(pathData).map { it.value }.toList()
        val commands = mutableListOf<PathCommand>()
        var i = 0
        var cx = 0f
        var cy = 0f
        var startX = 0f
        var startY = 0f
        // Last cubic/quad control point for S/T smooth commands (null = treat as current point)
        var lastCtrlX: Float? = null
        var lastCtrlY: Float? = null
        var lastWasCubic = false
        var lastWasQuad = false
        var cmd = 'M'
        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length == 1 && token[0].isLetter()) {
                cmd = token[0]
                i++
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    if (cmd == 'm') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                    startX = cx
                    startY = cy
                    commands.add(PathCommand.MoveTo(cx, cy))
                    lastCtrlX = null
                    lastCtrlY = null
                    lastWasCubic = false
                    lastWasQuad = false
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    if (cmd == 'l') {
                        cx += x
                        cy += y
                    } else {
                        cx = x
                        cy = y
                    }
                    commands.add(PathCommand.LineTo(cx, cy))
                    lastWasCubic = false
                    lastWasQuad = false
                }
                'H', 'h' -> {
                    val x = tokens[i++].toFloat()
                    cx = if (cmd == 'h') cx + x else x
                    commands.add(PathCommand.LineTo(cx, cy))
                    lastWasCubic = false
                    lastWasQuad = false
                }
                'V', 'v' -> {
                    val y = tokens[i++].toFloat()
                    cy = if (cmd == 'v') cy + y else y
                    commands.add(PathCommand.LineTo(cx, cy))
                    lastWasCubic = false
                    lastWasQuad = false
                }
                'C', 'c' -> {
                    val x1 = tokens[i++].toFloat()
                    val y1 = tokens[i++].toFloat()
                    val x2 = tokens[i++].toFloat()
                    val y2 = tokens[i++].toFloat()
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val abs = cmd == 'C'
                    val ax1 = if (abs) x1 else cx + x1
                    val ay1 = if (abs) y1 else cy + y1
                    val ax2 = if (abs) x2 else cx + x2
                    val ay2 = if (abs) y2 else cy + y2
                    cx = if (abs) x else cx + x
                    cy = if (abs) y else cy + y
                    commands.add(PathCommand.CubicTo(ax1, ay1, ax2, ay2, cx, cy))
                    lastCtrlX = ax2
                    lastCtrlY = ay2
                    lastWasCubic = true
                    lastWasQuad = false
                }
                'S', 's' -> {
                    val x2 = tokens[i++].toFloat()
                    val y2 = tokens[i++].toFloat()
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val abs = cmd == 'S'
                    val ax1 = if (lastWasCubic && lastCtrlX != null && lastCtrlY != null) {
                        2f * cx - lastCtrlX
                    } else {
                        cx
                    }
                    val ay1 = if (lastWasCubic && lastCtrlX != null && lastCtrlY != null) {
                        2f * cy - lastCtrlY
                    } else {
                        cy
                    }
                    val ax2 = if (abs) x2 else cx + x2
                    val ay2 = if (abs) y2 else cy + y2
                    cx = if (abs) x else cx + x
                    cy = if (abs) y else cy + y
                    commands.add(PathCommand.CubicTo(ax1, ay1, ax2, ay2, cx, cy))
                    lastCtrlX = ax2
                    lastCtrlY = ay2
                    lastWasCubic = true
                    lastWasQuad = false
                }
                'Q', 'q' -> {
                    val x1 = tokens[i++].toFloat()
                    val y1 = tokens[i++].toFloat()
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val abs = cmd == 'Q'
                    val qx = if (abs) x1 else cx + x1
                    val qy = if (abs) y1 else cy + y1
                    val ex = if (abs) x else cx + x
                    val ey = if (abs) y else cy + y
                    // Quadratic → cubic
                    val ax1 = cx + 2f / 3f * (qx - cx)
                    val ay1 = cy + 2f / 3f * (qy - cy)
                    val ax2 = ex + 2f / 3f * (qx - ex)
                    val ay2 = ey + 2f / 3f * (qy - ey)
                    commands.add(PathCommand.CubicTo(ax1, ay1, ax2, ay2, ex, ey))
                    cx = ex
                    cy = ey
                    lastCtrlX = qx
                    lastCtrlY = qy
                    lastWasCubic = false
                    lastWasQuad = true
                }
                'T', 't' -> {
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val abs = cmd == 'T'
                    val qx = if (lastWasQuad && lastCtrlX != null && lastCtrlY != null) {
                        2f * cx - lastCtrlX
                    } else {
                        cx
                    }
                    val qy = if (lastWasQuad && lastCtrlX != null && lastCtrlY != null) {
                        2f * cy - lastCtrlY
                    } else {
                        cy
                    }
                    val ex = if (abs) x else cx + x
                    val ey = if (abs) y else cy + y
                    val ax1 = cx + 2f / 3f * (qx - cx)
                    val ay1 = cy + 2f / 3f * (qy - cy)
                    val ax2 = ex + 2f / 3f * (qx - ex)
                    val ay2 = ey + 2f / 3f * (qy - ey)
                    commands.add(PathCommand.CubicTo(ax1, ay1, ax2, ay2, ex, ey))
                    cx = ex
                    cy = ey
                    lastCtrlX = qx
                    lastCtrlY = qy
                    lastWasCubic = false
                    lastWasQuad = true
                }
                'A', 'a' -> {
                    val rx = tokens[i++].toFloat()
                    val ry = tokens[i++].toFloat()
                    val rotation = tokens[i++].toFloat()
                    val largeArc = tokens[i++].toFloat().toInt() != 0
                    val sweep = tokens[i++].toFloat().toInt() != 0
                    val x = tokens[i++].toFloat()
                    val y = tokens[i++].toFloat()
                    val endX = if (cmd == 'a') cx + x else x
                    val endY = if (cmd == 'a') cy + y else y
                    commands.addAll(arcToCubics(cx, cy, rx, ry, rotation, largeArc, sweep, endX, endY))
                    cx = endX
                    cy = endY
                    lastWasCubic = false
                    lastWasQuad = false
                }
                'Z', 'z' -> {
                    commands.add(PathCommand.Close)
                    cx = startX
                    cy = startY
                    lastWasCubic = false
                    lastWasQuad = false
                }
                else -> error("Unsupported path command '$cmd' in: ${pathData.take(80)}")
            }
        }
        return commands
    }

    /** SVG elliptical arc → cubic Béziers (endpoint parameterization). */
    private fun arcToCubics(
        x1: Float,
        y1: Float,
        rxIn: Float,
        ryIn: Float,
        angleDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x2: Float,
        y2: Float,
    ): List<PathCommand> {
        if (rxIn == 0f || ryIn == 0f || (x1 == x2 && y1 == y2)) {
            return listOf(PathCommand.LineTo(x2, y2))
        }
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        val phi = angleDeg * PI.toFloat() / 180f
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x1 - x2) / 2f
        val dy2 = (y1 - y2) / 2f
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        var rxSq = rx * rx
        var rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p
        val lambda = x1pSq / rxSq + y1pSq / rySq
        if (lambda > 1f) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
            rxSq = rx * rx
            rySq = ry * ry
        }

        val sign = if (largeArc == sweep) -1f else 1f
        val num = max(0f, rxSq * rySq - rxSq * y1pSq - rySq * x1pSq)
        val den = rxSq * y1pSq + rySq * x1pSq
        val coef = if (den == 0f) 0f else sign * sqrt(num / den)
        val cxp = coef * (rx * y1p) / ry
        val cyp = coef * -(ry * x1p) / rx

        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2f

        fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val n = sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
            if (n == 0f) return 0f
            var cosA = (ux * vx + uy * vy) / n
            cosA = cosA.coerceIn(-1f, 1f)
            val a = kotlin.math.acos(cosA.toDouble()).toFloat()
            return if (ux * vy - uy * vx < 0) -a else a
        }

        val theta1 = angle(1f, 0f, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dTheta > 0) dTheta -= (2 * PI).toFloat()
        if (sweep && dTheta < 0) dTheta += (2 * PI).toFloat()

        val segments = max(1, ceil(abs(dTheta) / (PI.toFloat() / 2f)).toInt())
        val delta = dTheta / segments
        val t = (4f / 3f) * tan(delta / 4f)
        val out = ArrayList<PathCommand>(segments)
        var a1 = theta1
        for (s in 0 until segments) {
            val a2 = a1 + delta
            fun pt(a: Float): Pair<Float, Float> {
                val cosA = cos(a)
                val sinA = sin(a)
                return (cx + cosPhi * rx * cosA - sinPhi * ry * sinA) to
                    (cy + sinPhi * rx * cosA + cosPhi * ry * sinA)
            }
            fun dpt(a: Float): Pair<Float, Float> {
                val cosA = cos(a)
                val sinA = sin(a)
                // derivative
                val dx = -cosPhi * rx * sinA - sinPhi * ry * cosA
                val dy = -sinPhi * rx * sinA + cosPhi * ry * cosA
                return dx to dy
            }
            val (xStart, yStart) = pt(a1)
            val (xEnd, yEnd) = pt(a2)
            val (dx1, dy1) = dpt(a1)
            val (dx2, dy2) = dpt(a2)
            out.add(
                PathCommand.CubicTo(
                    xStart + t * dx1,
                    yStart + t * dy1,
                    xEnd - t * dx2,
                    yEnd - t * dy2,
                    xEnd,
                    yEnd,
                ),
            )
            a1 = a2
        }
        return out
    }

    private val TOKEN_REGEX = Regex("""[a-zA-Z]|-?\d*\.?\d+(?:[eE][+-]?\d+)?""")
}

internal fun androidColorToArgb(color: String): Int? {
    if (color.startsWith("#")) return colorFromString(color)
    if (color.startsWith("0x", ignoreCase = true) || color.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
        val hex = color.removePrefix("0x").removePrefix("0X")
        val v = hex.toULongOrNull(16)?.toLong() ?: return null
        val a = ((v shr 24) and 0xFF).toInt()
        val r = ((v shr 16) and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = (v and 0xFF).toInt()
        // If only 6 hex digits, treat as opaque RGB
        return if (hex.length <= 6) (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        else (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return null
}

internal fun parseAndroidFloat(value: String): Float {
    val trimmed = value.trim()
    // Hex float-bits (from typed AXML TYPE_FLOAT). Do NOT strip trailing a-f as "units".
    if (trimmed.startsWith("0x", ignoreCase = true) || trimmed.startsWith("0X")) {
        val hex = trimmed.removePrefix("0x").removePrefix("0X")
        val raw = hex.toULongOrNull(16)?.toInt() ?: return 0f
        return Float.fromBits(raw)
    }
    // Dimension / fraction strings: "12.5dp", "50%", etc.
    val noUnit = trimmed.replace(Regex("(?i)(dp|sp|px|in|mm|pt|%)$"), "")
    noUnit.toFloatOrNull()?.let { return it }
    // bare int bits from typed float attributes
    noUnit.toIntOrNull()?.let { bits ->
        val asFloat = Float.fromBits(bits)
        if (asFloat.isFinite() && abs(asFloat) < 1e6f) return asFloat
    }
    return 0f
}

internal fun sampleGradient(stops: List<GradientStop>, t: Float): Int {
    if (stops.isEmpty()) return 0
    val x = t.coerceIn(0f, 1f)
    if (x <= stops.first().offset) return stops.first().argb
    if (x >= stops.last().offset) return stops.last().argb
    for (i in 0 until stops.lastIndex) {
        val a = stops[i]
        val b = stops[i + 1]
        if (x >= a.offset && x <= b.offset) {
            val span = b.offset - a.offset
            if (span <= 1e-6f) return a.argb
            return lerpColor(a.argb, b.argb, (x - a.offset) / span)
        }
    }
    return stops.last().argb
}

private fun lerpColor(c0: Int, c1: Int, t: Float): Int {
    // Premultiplied sRGB lerp (Android VectorDrawable / earlier golden dumps).
    val a0 = ((c0 ushr 24) and 0xFF) / 255f
    val a1 = ((c1 ushr 24) and 0xFF) / 255f
    val r0 = ((c0 shr 16) and 0xFF) * a0
    val g0 = ((c0 shr 8) and 0xFF) * a0
    val b0 = (c0 and 0xFF) * a0
    val r1 = ((c1 shr 16) and 0xFF) * a1
    val g1 = ((c1 shr 8) and 0xFF) * a1
    val b1 = (c1 and 0xFF) * a1
    val inv = 1f - t
    val a = a0 * inv + a1 * t
    if (a <= 1e-6f) return 0
    val r = ((r0 * inv + r1 * t) / a + 0.5f).toInt().coerceIn(0, 255)
    val g = ((g0 * inv + g1 * t) / a + 0.5f).toInt().coerceIn(0, 255)
    val b = ((b0 * inv + b1 * t) / a + 0.5f).toInt().coerceIn(0, 255)
    val aa = (a * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (aa shl 24) or (r shl 16) or (g shl 8) or b
}
