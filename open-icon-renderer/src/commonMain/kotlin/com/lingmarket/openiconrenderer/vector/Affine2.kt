package com.lingmarket.openiconrenderer.vector

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 2D affine transform: `x' = a x + b y + tx`, `y' = c x + d y + ty`.
 *
 * [compose] is `this ∘ other` (apply [other] first). [postTranslate]/[postScale]/[postRotate]
 * match Android `Matrix.post*` (`M' = T * M`).
 */
internal data class Affine2(
    val a: Float,
    val b: Float,
    val tx: Float,
    val c: Float,
    val d: Float,
    val ty: Float,
) {
    fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
    fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

    fun compose(other: Affine2): Affine2 = Affine2(
        a = a * other.a + b * other.c,
        b = a * other.b + b * other.d,
        tx = a * other.tx + b * other.ty + tx,
        c = c * other.a + d * other.c,
        d = c * other.b + d * other.d,
        ty = c * other.tx + d * other.ty + ty,
    )

    fun postTranslate(dx: Float, dy: Float): Affine2 =
        Affine2(1f, 0f, dx, 0f, 1f, dy).compose(this)

    fun postScale(sx: Float, sy: Float): Affine2 =
        Affine2(sx, 0f, 0f, 0f, sy, 0f).compose(this)

    fun postRotate(degrees: Float): Affine2 {
        if (degrees == 0f) return this
        val r = degrees * (PI / 180.0)
        val cos = cos(r).toFloat()
        val sin = sin(r).toFloat()
        return Affine2(cos, -sin, 0f, sin, cos, 0f).compose(this)
    }

    fun meanScale(): Float {
        val sx = sqrt(a * a + c * c)
        val sy = sqrt(b * b + d * d)
        return (sx + sy) * 0.5f
    }

    fun mapCommands(commands: List<PathCommand>): List<PathCommand> {
        if (this == IDENTITY) return commands
        return commands.map { cmd ->
            when (cmd) {
                is PathCommand.MoveTo -> PathCommand.MoveTo(mapX(cmd.x, cmd.y), mapY(cmd.x, cmd.y))
                is PathCommand.LineTo -> PathCommand.LineTo(mapX(cmd.x, cmd.y), mapY(cmd.x, cmd.y))
                is PathCommand.CubicTo -> PathCommand.CubicTo(
                    mapX(cmd.x1, cmd.y1), mapY(cmd.x1, cmd.y1),
                    mapX(cmd.x2, cmd.y2), mapY(cmd.x2, cmd.y2),
                    mapX(cmd.x, cmd.y), mapY(cmd.x, cmd.y),
                )
                PathCommand.Close -> PathCommand.Close
            }
        }
    }

    fun mapFill(fill: FillPaint?): FillPaint? {
        if (fill == null || this == IDENTITY) return fill
        return when (fill) {
            is FillPaint.Solid -> fill
            is FillPaint.LinearGradient -> fill.copy(
                x0 = mapX(fill.x0, fill.y0),
                y0 = mapY(fill.x0, fill.y0),
                x1 = mapX(fill.x1, fill.y1),
                y1 = mapY(fill.x1, fill.y1),
            )
            is FillPaint.RadialGradient -> fill.copy(
                cx = mapX(fill.cx, fill.cy),
                cy = mapY(fill.cx, fill.cy),
                radius = fill.radius * meanScale(),
            )
        }
    }

    companion object {
        val IDENTITY = Affine2(1f, 0f, 0f, 0f, 1f, 0f)

        /**
         * Android `VectorDrawable.VGroup.updateLocalMatrix`:
         * `T(translate+pivot) * R * S * T(-pivot)`.
         */
        fun androidGroup(
            translateX: Float,
            translateY: Float,
            scaleX: Float,
            scaleY: Float,
            rotation: Float,
            pivotX: Float,
            pivotY: Float,
        ): Affine2 = IDENTITY
            .postTranslate(-pivotX, -pivotY)
            .postScale(scaleX, scaleY)
            .postRotate(rotation)
            .postTranslate(translateX + pivotX, translateY + pivotY)
    }
}
