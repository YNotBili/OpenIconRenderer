package com.lingmarket.openiconrenderer.util

import com.lingmarket.openiconrenderer.simd.oir_pack_argb_to_rgba_filter_none
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned

@OptIn(ExperimentalForeignApi::class)
internal actual fun packArgbToRgbaFilterNone(
    pixels: IntArray,
    raw: ByteArray,
    width: Int,
    height: Int,
) {
    if (width <= 0 || height <= 0) return
    pixels.usePinned { pp ->
        raw.usePinned { rp ->
            oir_pack_argb_to_rgba_filter_none(
                pp.addressOf(0),
                rp.addressOf(0).reinterpret(),
                width,
                height,
            )
        }
    }
}
