package com.lingmarket.openiconrenderer.util

import com.lingmarket.openiconrenderer.simd.oir_fill_linear_lut_span
import com.lingmarket.openiconrenderer.simd.oir_fill_radial_lut_span
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal actual fun fillLinearLutSpan(
    pixels: IntArray,
    start: Int,
    count: Int,
    lut: IntArray,
    t0: Float,
    dt: Float,
) {
    if (count <= 0) return
    pixels.usePinned { pp ->
        lut.usePinned { lp ->
            oir_fill_linear_lut_span(pp.addressOf(0), start, count, lp.addressOf(0), t0, dt)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
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
    pixels.usePinned { pp ->
        lut.usePinned { lp ->
            oir_fill_radial_lut_span(
                pp.addressOf(0), start, count, lp.addressOf(0),
                x0Center, y, cx, cy, invR,
            )
        }
    }
}
