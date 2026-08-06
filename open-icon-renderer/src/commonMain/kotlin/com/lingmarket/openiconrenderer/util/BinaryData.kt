package com.lingmarket.openiconrenderer.util

/**
 * Random-access bytes for APK/ZIP parsing. Heap-backed or OS-mapped (mmap);
 * mapped sources page in on demand instead of copying the whole APK into the heap.
 */
internal interface BinaryData {
    val size: Int
    operator fun get(index: Int): Byte
    fun copyOfRange(fromIndex: Int, toIndex: Int): ByteArray
    fun decodeToString(fromIndex: Int, toIndex: Int): String
    /** Non-null when this is a thin wrapper over a heap [ByteArray] (zero-copy mapEntry). */
    fun asHeapArrayOrNull(): ByteArray? = null
    fun close() {}
}

internal class HeapBinaryData(private val bytes: ByteArray) : BinaryData {
    override val size: Int get() = bytes.size
    override fun get(index: Int): Byte = bytes[index]
    override fun copyOfRange(fromIndex: Int, toIndex: Int): ByteArray =
        bytes.copyOfRange(fromIndex, toIndex)
    override fun decodeToString(fromIndex: Int, toIndex: Int): String =
        bytes.decodeToString(fromIndex, toIndex)
    override fun asHeapArrayOrNull(): ByteArray = bytes
}

internal fun BinaryData.u16LE(index: Int): Int =
    (this[index].toInt() and 0xFF) or ((this[index + 1].toInt() and 0xFF) shl 8)

internal fun BinaryData.u32LE(index: Int): Int =
    (this[index].toInt() and 0xFF) or
        ((this[index + 1].toInt() and 0xFF) shl 8) or
        ((this[index + 2].toInt() and 0xFF) shl 16) or
        ((this[index + 3].toInt() and 0xFF) shl 24)
