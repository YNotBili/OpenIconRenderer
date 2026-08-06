package com.lingmarket.openiconrenderer.util

/** Opaque linear gradient span: pixels[start + i] = lut[clamp(t0 + i*dt)], lut size 256. */
internal expect fun fillLinearLutSpan(
    pixels: IntArray,
    start: Int,
    count: Int,
    lut: IntArray,
    t0: Float,
    dt: Float,
)

/** Opaque radial gradient span along +x at fixed [y]. */
internal expect fun fillRadialLutSpan(
    pixels: IntArray,
    start: Int,
    count: Int,
    lut: IntArray,
    x0Center: Float,
    y: Float,
    cx: Float,
    cy: Float,
    invR: Float,
)
