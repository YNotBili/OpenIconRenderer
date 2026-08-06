package com.lingmarket.openiconrenderer.platform

import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.MappedBinaryData
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell

@OptIn(ExperimentalForeignApi::class)
internal actual fun readFileBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val sizeLong = ftell(file)
        if (sizeLong < 0) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val size = sizeLong.toInt()
        if (size.toLong() != sizeLong) return null
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = buf.usePinned { pinned ->
                fread(pinned.addressOf(offset), 1.convert(), (size - offset).convert(), file).toInt()
            }
            if (n <= 0) break
            offset += n
        }
        if (offset != size) return null
        buf
    } finally {
        fclose(file)
    }
}

internal actual fun openBinaryData(path: String): BinaryData? = MappedBinaryData.open(path)
