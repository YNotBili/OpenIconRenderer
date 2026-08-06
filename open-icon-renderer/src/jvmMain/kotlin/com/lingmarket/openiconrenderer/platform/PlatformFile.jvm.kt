package com.lingmarket.openiconrenderer.platform

import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun readFileBytes(path: String): ByteArray? =
    runCatching { Files.readAllBytes(Paths.get(path)) }.getOrNull()

internal actual fun openBinaryData(path: String): BinaryData? =
    runCatching { MappedFileBinaryData.open(path) }.getOrNull()
        ?: readFileBytes(path)?.let { HeapBinaryData(it) }

/**
 * FileChannel mmap: pages fault in on demand (same idea as POSIX mmap).
 */
private class MappedFileBinaryData private constructor(
    private val raf: RandomAccessFile,
    private val buffer: java.nio.MappedByteBuffer,
    override val size: Int,
) : BinaryData {
    override fun get(index: Int): Byte = buffer.get(index)

    override fun copyOfRange(fromIndex: Int, toIndex: Int): ByteArray {
        require(fromIndex in 0..toIndex && toIndex <= size)
        val len = toIndex - fromIndex
        val out = ByteArray(len)
        if (len == 0) return out
        val dup = buffer.duplicate()
        dup.position(fromIndex)
        dup.get(out)
        return out
    }

    override fun decodeToString(fromIndex: Int, toIndex: Int): String =
        copyOfRange(fromIndex, toIndex).decodeToString()

    override fun asHeapArrayOrNull(): ByteArray? = null

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        fun open(path: String): MappedFileBinaryData {
            val raf = RandomAccessFile(path, "r")
            val sizeLong = raf.length()
            require(sizeLong > 0 && sizeLong <= Int.MAX_VALUE) { "bad size $sizeLong" }
            val size = sizeLong.toInt()
            val buffer = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, sizeLong)
            return MappedFileBinaryData(raf, buffer, size)
        }
    }
}
