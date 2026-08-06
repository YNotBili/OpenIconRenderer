package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.vector.VectorPath

/**
 * Parsed launcher-icon geometry / bitmaps, independent of output size.
 * Graphite-inspired: record once (resolve), render many sizes.
 */
internal sealed class IconRecording {
    abstract val iconRef: String?
    abstract val sourcePath: String?

    /** Adaptive icon with size-independent FG vector (preferred hot path). */
    data class Adaptive(
        override val iconRef: String?,
        override val sourcePath: String?,
        val background: Background,
        val foreground: Foreground,
    ) : IconRecording()

    /** Already-rasterized drawable (mipmap / webp / non-adaptive XML). */
    data class Raster(
        override val iconRef: String?,
        override val sourcePath: String?,
        val bitmap: RgbaBitmap,
    ) : IconRecording()

    sealed class Background {
        data class Solid(val argb: Int) : Background()
        data class Bitmap(val bitmap: RgbaBitmap) : Background()
        data object None : Background()
    }

    sealed class Foreground {
        data class Vector(
            val paths: List<VectorPath>,
            val viewportWidth: Float,
            val viewportHeight: Float,
        ) : Foreground()
        data class Bitmap(val bitmap: RgbaBitmap) : Foreground()
        data object None : Foreground()
    }
}
