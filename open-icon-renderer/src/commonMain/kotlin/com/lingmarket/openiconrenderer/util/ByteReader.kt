package com.lingmarket.openiconrenderer.util

internal class ByteReader(
    private val data: ByteArray,
    private var offset: Int = 0,
    private val end: Int = data.size,
) {
    val position: Int get() = offset
    val remaining: Int get() = end - offset
    val size: Int get() = end

    fun slice(from: Int, to: Int): ByteArray = data.copyOfRange(from, to)

    fun readU8(): Int {
        require(offset < end) { "Unexpected EOF" }
        return data[offset++].toInt() and 0xFF
    }

    fun readU16LE(): Int {
        val b0 = readU8()
        val b1 = readU8()
        return b0 or (b1 shl 8)
    }

    fun readU32LE(): Int {
        val b0 = readU8()
        val b1 = readU8()
        val b2 = readU8()
        val b3 = readU8()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readU32LEUnsigned(): Long = readU32LE().toLong() and 0xFFFFFFFFL

    fun readS32LE(): Int = readU32LE()

    fun readBytes(count: Int): ByteArray {
        require(offset + count <= end) { "Unexpected EOF" }
        val out = data.copyOfRange(offset, offset + count)
        offset += count
        return out
    }

    fun skip(count: Int) {
        require(offset + count <= end) { "Unexpected EOF" }
        offset += count
    }

    fun seek(position: Int) {
        require(position in 0..end) { "Invalid seek position: $position" }
        offset = position
    }

    fun hasRemaining(): Boolean = offset < end
}

internal fun ByteArray.u16LE(index: Int): Int =
    (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)

internal fun ByteArray.u32LE(index: Int): Int =
    (this[index].toInt() and 0xFF) or
        ((this[index + 1].toInt() and 0xFF) shl 8) or
        ((this[index + 2].toInt() and 0xFF) shl 16) or
        ((this[index + 3].toInt() and 0xFF) shl 24)

private val CRC32_TABLE: IntArray = IntArray(256) { i ->
    var c = i
    repeat(8) {
        c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1
    }
    c
}

/** Raw CRC state (init with [CRC32_INIT]); finish with [crc32Finish]. */
internal const val CRC32_INIT = -1 // 0xFFFFFFFF

internal fun crc32Update(crc: Int, data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
    var c = crc
    val end = offset + length
    var i = offset
    while (i < end) {
        c = CRC32_TABLE[(c xor (data[i].toInt() and 0xFF)) and 0xFF] xor (c ushr 8)
        i++
    }
    return c
}

internal fun crc32Finish(crc: Int): Int = crc.inv()

internal fun crc32(data: ByteArray): Int = crc32Finish(crc32Update(CRC32_INIT, data))

internal fun crc32(data: ByteArray, offset: Int, length: Int): Int =
    crc32Finish(crc32Update(CRC32_INIT, data, offset, length))

internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
