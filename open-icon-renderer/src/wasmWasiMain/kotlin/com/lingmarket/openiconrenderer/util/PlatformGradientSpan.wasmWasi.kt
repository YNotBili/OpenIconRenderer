package com.lingmarket.openiconrenderer.util

internal actual fun fillLinearLutSpan(
    pixels: IntArray,
    start: Int,
    count: Int,
    lut: IntArray,
    t0: Float,
    dt: Float,
) {
    var t = t0
    var i = start
    val end = start + count
    while (i < end) {
        val idx = (t.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        pixels[i] = lut[idx]
        t += dt
        i++
    }
}

internal actual fun fillRadialLutSpan(
    pixels: IntArray,
    start: Int,
    count: Int,
    lut: IntArray,
    x0Center: Float,
    y: Float,
    cx: Float,
    cy: Float,
    invR: Float,
) {
    if (count <= 0) return
    if (invR <= 0f) {
        val c = lut[0]
        pixels.fill(c, start, start + count)
        return
    }
    val dy = y - cy
    val dy2 = dy * dy
    var i = 0
    while (i < count) {
        val dx = (x0Center + i) - cx
        val t = invR * kotlin.math.sqrt(dx * dx + dy2)
        val idx = (t.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
        pixels[start + i] = lut[idx]
        i++
    }
}
