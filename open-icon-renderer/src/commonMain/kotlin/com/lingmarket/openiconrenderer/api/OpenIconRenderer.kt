package com.lingmarket.openiconrenderer.api

import com.lingmarket.openiconrenderer.apk.ApksExtractor
import com.lingmarket.openiconrenderer.apk.ApkIconExtractor
import com.lingmarket.openiconrenderer.platform.openBinaryData
import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.StageClock

object OpenIconRenderer {
    fun openSession(apkPath: String): IconSession? = IconSession.open(apkPath)

    fun openSession(apkBytes: ByteArray): IconSession? = IconSession.open(apkBytes)

    fun inspectLauncherIcon(apkBytes: ByteArray): IconInspectionResult? {
        val data = normalizeApkData(HeapBinaryData(apkBytes)) ?: return null
        return try {
            inspectWith(data)
        } finally {
            data.close()
        }
    }

    /** Path-based inspect: mmap/on-demand I/O (no full-APK heap copy on native/JVM). */
    fun inspectLauncherIcon(apkPath: String): IconInspectionResult? {
        val data = openNormalizedApk(apkPath) ?: return null
        return try {
            inspectWith(data)
        } finally {
            data.close()
        }
    }

    fun extractLauncherIcon(
        apkBytes: ByteArray,
        options: IconExtractOptions = IconExtractOptions(),
    ): IconExtractResult? {
        val data = normalizeApkData(HeapBinaryData(apkBytes)) ?: return null
        return try {
            extractWith(data, options)
        } finally {
            data.close()
        }
    }

    /** Path-based extract: mmap/on-demand I/O (no full-APK heap copy on native/JVM). */
    fun extractLauncherIcon(
        apkPath: String,
        options: IconExtractOptions = IconExtractOptions(),
    ): IconExtractResult? {
        val data = openNormalizedApk(apkPath) ?: return null
        return try {
            extractWith(data, options)
        } finally {
            data.close()
        }
    }

    fun extractLauncherIconPng(
        apkBytes: ByteArray,
        options: IconExtractOptions = IconExtractOptions(),
    ): ByteArray? = extractLauncherIcon(apkBytes, options)?.pngBytes

    fun extractLauncherIconPng(
        apkPath: String,
        options: IconExtractOptions = IconExtractOptions(),
    ): ByteArray? = extractLauncherIcon(apkPath, options)?.pngBytes

    private fun inspectWith(data: BinaryData): IconInspectionResult? {
        val inspection = ApkIconExtractor(
            data,
            IconExtractOptions(outputSize = 1, preferAdaptive = true, mask = IconMask.NONE),
        ).inspect()
        return IconInspectionResult(
            iconRef = inspection.iconRef,
            resolvedPath = inspection.resolvedPath,
        )
    }

    private fun extractWith(data: BinaryData, options: IconExtractOptions): IconExtractResult? {
        val stages = if (options.profileStages) StageClock() else null
        val extracted = ApkIconExtractor(data, options, stages).extract() ?: return null
        val png = if (stages != null) {
            stages.measure("png") { extracted.toPng() }
        } else {
            extracted.toPng()
        }
        stages?.report()
        return IconExtractResult(
            pngBytes = png,
            width = options.outputSize,
            height = options.outputSize,
            sourcePath = extracted.sourcePath,
        )
    }

    private fun openNormalizedApk(path: String): BinaryData? {
        val opened = openBinaryData(path) ?: return null
        return try {
            val normalized = normalizeApkData(opened)
            if (normalized == null) {
                opened.close()
                null
            } else if (normalized !== opened) {
                opened.close()
                normalized
            } else {
                normalized
            }
        } catch (t: Throwable) {
            opened.close()
            throw t
        }
    }

    private fun normalizeApkData(data: BinaryData): BinaryData? {
        if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
            return data
        }
        if (data.size >= 4 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte()) {
            // .apks: need a contiguous heap buffer for nested ZIP extract today.
            val bytes = data.asHeapArrayOrNull() ?: data.copyOfRange(0, data.size)
            val apk = ApksExtractor.extractBaseApkBytes(bytes) ?: return null
            return HeapBinaryData(apk)
        }
        return data
    }
}
