package com.lingmarket.openiconrenderer.benchmark

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.IconMask
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite

@OptIn(ExperimentalForeignApi::class)
fun dumpIcons(apkPath: String, outDir: String) {
    val inspection = OpenIconRenderer.inspectLauncherIcon(apkPath)
    println("iconRef=${inspection?.iconRef} path=${inspection?.resolvedPath}")
    for (size in listOf(512, 1024, 2048)) {
        val result = OpenIconRenderer.extractLauncherIcon(
            apkPath,
            IconExtractOptions(
                outputSize = size,
                densityDpi = 480,
                sdkVersion = 35,
                preferAdaptive = true,
                mask = IconMask.CIRCLE,
            ),
        ) ?: error("render failed $size")
        val path = "$outDir/fenix-native-${size}.png"
        writeAllBytesLocal(path, result.pngBytes)
        println("wrote $path (${result.pngBytes.size} bytes) source=${result.sourcePath}")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAllBytesLocal(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("cannot write $path")
    try {
        bytes.usePinned { pinned ->
            val n = fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
            require(n == bytes.size)
        }
    } finally {
        fclose(file)
    }
}
