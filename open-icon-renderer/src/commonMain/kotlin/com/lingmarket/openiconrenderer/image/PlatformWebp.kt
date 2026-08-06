package com.lingmarket.openiconrenderer.image

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap

/**
 * Decode WebP. When [targetWidth]/[targetHeight] > 0, native backends may scale during decode
 * (libwebp), avoiding a separate resize pass.
 */
internal expect fun decodeWebpNative(
    data: ByteArray,
    targetWidth: Int = 0,
    targetHeight: Int = 0,
): RgbaBitmap?
