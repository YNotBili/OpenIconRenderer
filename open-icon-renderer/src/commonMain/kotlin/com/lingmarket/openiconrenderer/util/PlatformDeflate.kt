package com.lingmarket.openiconrenderer.util

/**
 * Raw DEFLATE inflate (ZIP method 8 / PNG IDAT after zlib unwrap).
 * [uncompressedSize] when known (ZIP local header) enables faster one-shot decompressors.
 */
internal expect fun inflateRawDeflate(input: ByteArray, uncompressedSize: Int = -1): ByteArray

/**
 * zlib-wrapped DEFLATE for PNG IDAT. [bytes] may be a reused scratch; only
 * `bytes[0, length)` is valid. Native uses libdeflate (fast Adler-32).
 */
internal class ZlibBytes(val bytes: ByteArray, val length: Int)

internal expect fun compressZlib(input: ByteArray, level: Int = 6, size: Int = -1): ZlibBytes

/**
 * PNG/ITU CRC-32 (same as zlib crc32 / libdeflate_crc32).
 * Pass previous return value to continue; start with 0.
 */
internal expect fun platformCrc32(
    crc: Int,
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
): Int

/**
 * Adler-32 (zlib). Start with 1. Native: libdeflate_adler32.
 */
internal expect fun platformAdler32(
    adler: Int,
    data: ByteArray,
    offset: Int = 0,
    length: Int = data.size - offset,
): Int
