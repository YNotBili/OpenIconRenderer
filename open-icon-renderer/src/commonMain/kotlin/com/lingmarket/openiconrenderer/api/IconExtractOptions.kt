package com.lingmarket.openiconrenderer.api

enum class IconMask {
    NONE,
    CIRCLE,
}

data class IconExtractOptions(
    val outputSize: Int = 432,
    /** Target screen density in dpi (e.g. 480 = xxhdpi). Used when path qualifiers exist. */
    val densityDpi: Int = 480,
    /** Target SDK for themed/monochrome icon selection (reserved / future). */
    val sdkVersion: Int = 35,
    /** Prefer anydpi adaptive-icon XML over pre-rasterized mipmap bitmaps. */
    val preferAdaptive: Boolean = true,
    val mask: IconMask = IconMask.CIRCLE,
    val verbose: Boolean = false,
    /** Print per-stage wall times (zip / arsc / resolve / tess / mask / png) to stdout. */
    val profileStages: Boolean = false,
)

data class IconExtractResult(
    val pngBytes: ByteArray,
    val width: Int,
    val height: Int,
    val sourcePath: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IconExtractResult) return false
        return width == other.width && height == other.height && sourcePath == other.sourcePath && pngBytes.contentEquals(other.pngBytes)
    }

    override fun hashCode(): Int {
        var result = pngBytes.contentHashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + (sourcePath?.hashCode() ?: 0)
        return result
    }
}
