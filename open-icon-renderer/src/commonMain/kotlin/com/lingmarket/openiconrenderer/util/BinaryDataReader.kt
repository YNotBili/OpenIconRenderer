package com.lingmarket.openiconrenderer.util

/**
 * Sequential reader over [BinaryData] with a sliding window so mmap-backed ARSC
 * indexing does not memcpy the whole table (Fenix ~17MB) onto the heap.
 * Seeks past unread ranges (e.g. the global string pool body) are O(1).
 */
internal class BinaryDataReader(
    private val data: BinaryData,
    private var offset: Int = 0,
    private val end: Int = data.size,
) {
    private val heap: ByteArray? = data.asHeapArrayOrNull()
    private var window: ByteArray = EMPTY
    private var wBase = 0
    private var wEnd = 0

    val position: Int get() = offset

    fun readU8(): Int {
        require(offset < end) { "Unexpected EOF" }
        val v = if (heap != null) {
            heap[offset].toInt() and 0xFF
        } else {
            ensure(1)
            window[offset - wBase].toInt() and 0xFF
        }
        offset++
        return v
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

    fun skip(count: Int) {
        require(offset + count <= end) { "Unexpected EOF" }
        offset += count
    }

    fun seek(position: Int) {
        require(position in 0..end) { "Invalid seek position: $position" }
        offset = position
    }

    private fun ensure(n: Int) {
        if (offset >= wBase && offset + n <= wEnd) return
        val start = offset
        val loadEnd = minOf(end, start + WINDOW)
        require(loadEnd - start >= n) { "Unexpected EOF" }
        window = data.copyOfRange(start, loadEnd)
        wBase = start
        wEnd = loadEnd
    }

    companion object {
        private const val WINDOW = 128 * 1024
        private val EMPTY = ByteArray(0)
    }
}
