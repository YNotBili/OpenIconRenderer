package com.lingmarket.openiconrenderer.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import libdeflate.LIBDEFLATE_INSUFFICIENT_SPACE
import libdeflate.LIBDEFLATE_SUCCESS
import libdeflate.libdeflate_adler32
import libdeflate.libdeflate_alloc_compressor
import libdeflate.libdeflate_alloc_decompressor
import libdeflate.libdeflate_crc32
import libdeflate.libdeflate_deflate_decompress
import libdeflate.libdeflate_free_compressor
import libdeflate.libdeflate_free_decompressor
import libdeflate.libdeflate_zlib_compress
import libdeflate.libdeflate_zlib_compress_bound

@OptIn(ExperimentalForeignApi::class)
internal actual fun inflateRawDeflate(input: ByteArray, uncompressedSize: Int): ByteArray {
    val decompressor = libdeflate_alloc_decompressor()
        ?: error("libdeflate_alloc_decompressor failed")
    try {
        if (uncompressedSize > 0) {
            val out = ByteArray(uncompressedSize)
            input.usePinned { inPinned ->
                out.usePinned { outPinned ->
                    memScoped {
                        val actualSize = alloc<ULongVar>()
                        val result = libdeflate_deflate_decompress(
                            decompressor,
                            inPinned.addressOf(0),
                            input.size.convert(),
                            outPinned.addressOf(0),
                            out.size.convert(),
                            actualSize.ptr,
                        )
                        require(result == LIBDEFLATE_SUCCESS) {
                            "libdeflate_deflate_decompress failed: $result"
                        }
                        require(actualSize.value.toInt() == uncompressedSize) {
                            "unexpected inflate size ${actualSize.value} != $uncompressedSize"
                        }
                    }
                }
            }
            return out
        }

        var capacity = (input.size * 4).coerceAtLeast(64 * 1024)
        while (true) {
            val out = ByteArray(capacity)
            val outcome = input.usePinned { inPinned ->
                out.usePinned { outPinned ->
                    memScoped {
                        val actualSize = alloc<ULongVar>()
                        val result = libdeflate_deflate_decompress(
                            decompressor,
                            inPinned.addressOf(0),
                            input.size.convert(),
                            outPinned.addressOf(0),
                            out.size.convert(),
                            actualSize.ptr,
                        )
                        result to actualSize.value.toInt()
                    }
                }
            }
            when (outcome.first) {
                LIBDEFLATE_SUCCESS -> return out.copyOf(outcome.second)
                LIBDEFLATE_INSUFFICIENT_SPACE -> capacity *= 2
                else -> error("libdeflate_deflate_decompress failed: ${outcome.first}")
            }
        }
    } finally {
        libdeflate_free_decompressor(decompressor)
    }
}

@OptIn(ExperimentalForeignApi::class)
private object ZlibCompressorCache {
    private var level: Int = 1
    private var compressor = libdeflate_alloc_compressor(1)
        ?: error("libdeflate_alloc_compressor failed")
    private var outScratch: ByteArray? = null

    fun get(level: Int) = run {
        val lvl = level.coerceIn(0, 12)
        if (this.level == lvl) return@run compressor
        libdeflate_free_compressor(compressor)
        val next = libdeflate_alloc_compressor(lvl)
            ?: error("libdeflate_alloc_compressor failed")
        compressor = next
        this.level = lvl
        next
    }

    fun scratch(bound: Int): ByteArray {
        val cur = outScratch
        if (cur != null && cur.size >= bound) return cur
        val next = ByteArray(bound.coerceAtLeast(16))
        outScratch = next
        return next
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun compressZlib(input: ByteArray, level: Int, size: Int): ZlibBytes {
    val n = if (size < 0) input.size else size.coerceIn(0, input.size)
    val compressor = ZlibCompressorCache.get(level)
    val bound = libdeflate_zlib_compress_bound(compressor, n.convert()).toInt()
    val out = ZlibCompressorCache.scratch(bound)
    val written = input.usePinned { inPinned ->
        out.usePinned { outPinned ->
            libdeflate_zlib_compress(
                compressor,
                inPinned.addressOf(0),
                n.convert(),
                outPinned.addressOf(0),
                out.size.convert(),
            ).toInt()
        }
    }
    require(written > 0) { "libdeflate_zlib_compress failed" }
    return ZlibBytes(out, written)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformCrc32(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return crc
    require(offset >= 0 && length >= 0 && offset + length <= data.size)
    return data.usePinned { pinned ->
        libdeflate_crc32(
            crc.toUInt(),
            pinned.addressOf(offset),
            length.convert(),
        ).toInt()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun platformAdler32(adler: Int, data: ByteArray, offset: Int, length: Int): Int {
    if (length <= 0) return adler
    require(offset >= 0 && length >= 0 && offset + length <= data.size)
    return data.usePinned { pinned ->
        libdeflate_adler32(
            adler.toUInt(),
            pinned.addressOf(offset),
            length.convert(),
        ).toInt()
    }
}
