package com.lingmarket.openiconrenderer.api

import com.lingmarket.openiconrenderer.apk.ApkIconExtractor
import com.lingmarket.openiconrenderer.apk.ApkMetadataParser
import com.lingmarket.openiconrenderer.apk.ApksExtractor
import com.lingmarket.openiconrenderer.platform.openBinaryData
import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.StageClock
import com.lingmarket.openiconrenderer.zip.ZipArchive

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

    /**
     * Parse package / version / SDK / ABI / permissions and the launcher icon in **one** ZIP session.
     * Returns null only when the Manifest cannot yield a package name.
     * Permission extraction failures yield an empty permission list (never null metadata).
     */
    fun parseApkPreview(
        apkPath: String,
        options: IconExtractOptions = IconExtractOptions(),
    ): ApkPreview? {
        val data = openNormalizedApk(apkPath) ?: return null
        return try {
            parsePreviewWith(data, options, includeIcon = true)
        } finally {
            data.close()
        }
    }

    fun parseApkPreview(
        apkBytes: ByteArray,
        options: IconExtractOptions = IconExtractOptions(),
    ): ApkPreview? {
        val data = normalizeApkData(HeapBinaryData(apkBytes)) ?: return null
        return try {
            parsePreviewWith(data, options, includeIcon = true)
        } finally {
            data.close()
        }
    }

    /** Manifest / ABI / permissions only — skips launcher icon raster (cheapest upload validation path). */
    fun parseApkMetadata(apkPath: String): ApkMetadata? {
        val data = openNormalizedApk(apkPath) ?: return null
        return try {
            parsePreviewWith(data, IconExtractOptions(outputSize = 1), includeIcon = false)?.metadata
        } finally {
            data.close()
        }
    }

    fun parseApkMetadata(apkBytes: ByteArray): ApkMetadata? {
        val data = normalizeApkData(HeapBinaryData(apkBytes)) ?: return null
        return try {
            parsePreviewWith(data, IconExtractOptions(outputSize = 1), includeIcon = false)?.metadata
        } finally {
            data.close()
        }
    }

    private fun parsePreviewWith(
        data: BinaryData,
        options: IconExtractOptions,
        includeIcon: Boolean,
    ): ApkPreview? {
        val zip = ZipArchive(data)
        val parsed = ApkMetadataParser.parseWithResources(zip) ?: return null
        if (!includeIcon) {
            return ApkPreview(metadata = parsed.metadata, iconPng = null)
        }
        val iconPng = runCatching {
            ApkIconExtractor(
                apkData = data,
                options = options,
                sharedZip = zip,
                sharedResources = parsed.resources,
            ).extract(preferredIconRef = parsed.metadata.launcherIconRef)?.toPng()
        }.getOrNull()
        return ApkPreview(metadata = parsed.metadata, iconPng = iconPng)
    }

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
