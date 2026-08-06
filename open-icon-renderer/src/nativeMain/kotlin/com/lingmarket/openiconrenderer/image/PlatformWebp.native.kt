package com.lingmarket.openiconrenderer.image

import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import libwebp.MODE_RGBA
import libwebp.VP8_STATUS_OK
import libwebp.WEBP_DECODER_ABI_VERSION
import libwebp.WebPDecode
import libwebp.WebPDecodeRGBA
import libwebp.WebPDecoderConfig
import libwebp.WebPFree
import libwebp.WebPInitDecoderConfigInternal
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
internal actual fun decodeWebpNative(
    data: ByteArray,
    targetWidth: Int,
    targetHeight: Int,
): RgbaBitmap? {
    if (targetWidth > 0 && targetHeight > 0) {
        decodeScaled(data, targetWidth, targetHeight)?.let { return it }
    }
    return decodeFull(data)
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeFull(data: ByteArray): RgbaBitmap? = memScoped {
    val widthVar = alloc<kotlinx.cinterop.IntVar>()
    val heightVar = alloc<kotlinx.cinterop.IntVar>()
    val rgbaPtr = data.usePinned { pinned ->
        WebPDecodeRGBA(
            pinned.addressOf(0).reinterpret(),
            data.size.convert(),
            widthVar.ptr,
            heightVar.ptr,
        )
    } ?: return null
    try {
        rgbaToBitmap(rgbaPtr, widthVar.value, heightVar.value)
    } finally {
        WebPFree(rgbaPtr)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun decodeScaled(data: ByteArray, targetWidth: Int, targetHeight: Int): RgbaBitmap? = memScoped {
    val config = alloc<WebPDecoderConfig>()
    if (WebPInitDecoderConfigInternal(config.ptr, WEBP_DECODER_ABI_VERSION) == 0) return null
    config.options.use_scaling = 1
    config.options.scaled_width = targetWidth
    config.options.scaled_height = targetHeight
    config.output.colorspace = MODE_RGBA

    val stride = targetWidth * 4
    val byteCount = stride * targetHeight
    val rgba = ByteArray(byteCount)
    val status = rgba.usePinned { outPinned ->
        config.output.is_external_memory = 1
        config.output.u.RGBA.rgba = outPinned.addressOf(0).reinterpret()
        config.output.u.RGBA.stride = stride
        config.output.u.RGBA.size = byteCount.convert()
        data.usePinned { inPinned ->
            WebPDecode(
                inPinned.addressOf(0).reinterpret(),
                data.size.convert(),
                config.ptr,
            )
        }
    }
    if (status != VP8_STATUS_OK) return null
    bytesToBitmap(rgba, targetWidth, targetHeight)
}

@OptIn(ExperimentalForeignApi::class)
private fun rgbaToBitmap(rgbaPtr: kotlinx.cinterop.CPointer<kotlinx.cinterop.UByteVar>, width: Int, height: Int): RgbaBitmap? {
    if (width <= 0 || height <= 0) return null
    val bytes = width * height * 4
    val raw = ByteArray(bytes)
    raw.usePinned { outPinned ->
        memcpy(outPinned.addressOf(0), rgbaPtr, bytes.convert())
    }
    return bytesToBitmap(raw, width, height)
}

private fun bytesToBitmap(raw: ByteArray, width: Int, height: Int): RgbaBitmap {
    val pixelCount = width * height
    val pixels = IntArray(pixelCount)
    var p = 0
    for (i in 0 until pixelCount) {
        val r = raw[p++].toInt() and 0xFF
        val g = raw[p++].toInt() and 0xFF
        val b = raw[p++].toInt() and 0xFF
        val a = raw[p++].toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return RgbaBitmap(width, height, pixels)
}
