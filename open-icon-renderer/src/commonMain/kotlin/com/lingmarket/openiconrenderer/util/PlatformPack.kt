package com.lingmarket.openiconrenderer.util

/**
 * Pack ARGB8888 [pixels] into PNG filter-None raw buffer:
 * each row is `0` + width×RGBA bytes. [raw] must hold `height * (1 + width * 4)`.
 */
internal expect fun packArgbToRgbaFilterNone(
    pixels: IntArray,
    raw: ByteArray,
    width: Int,
    height: Int,
)
