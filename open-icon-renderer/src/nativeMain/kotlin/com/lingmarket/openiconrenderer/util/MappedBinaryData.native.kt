package com.lingmarket.openiconrenderer.util

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.MAP_FAILED
import platform.posix.MAP_PRIVATE
import platform.posix.O_RDONLY
import platform.posix.PROT_READ
import platform.posix.SEEK_END
import platform.posix.close
import platform.posix.lseek
import platform.posix.memcpy
import platform.posix.mmap
import platform.posix.munmap
import platform.posix.open

@OptIn(ExperimentalForeignApi::class)
internal class MappedBinaryData private constructor(
    private val ptr: CPointer<ByteVar>,
    override val size: Int,
    private val fd: Int,
) : BinaryData {
    private var closed = false

    override fun get(index: Int): Byte {
        require(!closed)
        require(index in 0 until size)
        return (ptr + index)!!.pointed.value
    }

    override fun copyOfRange(fromIndex: Int, toIndex: Int): ByteArray {
        require(!closed)
        require(fromIndex in 0..toIndex && toIndex <= size)
        val len = toIndex - fromIndex
        val out = ByteArray(len)
        if (len == 0) return out
        val src = (ptr + fromIndex)!!
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), src, len.convert())
        }
        return out
    }

    override fun decodeToString(fromIndex: Int, toIndex: Int): String =
        copyOfRange(fromIndex, toIndex).decodeToString()

    override fun asHeapArrayOrNull(): ByteArray? = null

    override fun close() {
        if (closed) return
        closed = true
        munmap(ptr, size.convert())
        close(fd)
    }

    companion object {
        fun open(path: String): MappedBinaryData? {
            val fd = open(path, O_RDONLY)
            if (fd < 0) return null
            val sizeLong = lseek(fd, 0, SEEK_END)
            if (sizeLong <= 0L || sizeLong > Int.MAX_VALUE) {
                close(fd)
                return null
            }
            val size = sizeLong.toInt()
            val raw = mmap(null, size.convert(), PROT_READ, MAP_PRIVATE, fd, 0)
            if (raw == null || raw == MAP_FAILED) {
                close(fd)
                return null
            }
            return MappedBinaryData(raw.reinterpret(), size, fd)
        }
    }
}
