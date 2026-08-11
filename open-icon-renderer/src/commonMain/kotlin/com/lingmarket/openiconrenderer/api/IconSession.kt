package com.lingmarket.openiconrenderer.api

import com.lingmarket.openiconrenderer.apk.ApkIconExtractor
import com.lingmarket.openiconrenderer.apk.ApksExtractor
import com.lingmarket.openiconrenderer.apk.IconRecording
import com.lingmarket.openiconrenderer.platform.openBinaryData
import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.StageClock

/**
 * Keeps ZIP/ARSC open and caches an [IconRecording] so multiple output sizes
 * only re-run tessellation / coverage / shade / mask / png.
 */
class IconSession private constructor(
    private val data: BinaryData,
    private val ownsData: Boolean,
) : AutoCloseable {
    private var closed = false
    private val extractor = ApkIconExtractor(data, IconExtractOptions(), stages = null)
    private var recording: IconRecording? = null
    private var recordedDensityDpi: Int = -1
    private var recordedSdk: Int = -1
    private var recordedPreferAdaptive: Boolean = true

    fun inspect(): IconInspectionResult? {
        checkOpen()
        extractor.stages = null
        extractor.updateOptions(
            IconExtractOptions(outputSize = 1, preferAdaptive = true, mask = IconMask.NONE),
        )
        val inspection = extractor.inspect()
        return IconInspectionResult(
            iconRef = inspection.iconRef,
            resolvedPath = inspection.resolvedPath,
        )
    }

    /** Package / label / version / SDK / ABIs from the already-open APK ZIP. */
    fun metadata(): ApkMetadata? {
        checkOpen()
        return extractor.parseMetadata()
    }

    /**
     * Resolve launcher icon into a size-independent recording (ZIP/ARSC once).
     * Subsequent [extract] calls reuse it when density/sdk/adaptive prefs match.
     */
    fun record(options: IconExtractOptions = IconExtractOptions()): IconRecordingHandle? {
        checkOpen()
        val stages = if (options.profileStages) StageClock() else null
        extractor.stages = stages
        extractor.updateOptions(options.copy(mask = IconMask.NONE))
        val rec = extractor.record() ?: return null
        storeRecording(rec, options)
        stages?.report(prefix = "[OpenIconRenderer record]")
        extractor.stages = null
        return IconRecordingHandle(rec.sourcePath, rec.iconRef)
    }

    fun extract(options: IconExtractOptions = IconExtractOptions()): IconExtractResult? {
        checkOpen()
        val stages = if (options.profileStages) StageClock() else null
        extractor.stages = stages
        val rec = ensureRecording(options) ?: return null
        extractor.updateOptions(options)
        val extracted = extractor.renderRecording(rec) ?: return null
        val png = if (stages != null) {
            stages.measure("png") { extracted.toPng() }
        } else {
            extracted.toPng()
        }
        stages?.report()
        extractor.stages = null
        return IconExtractResult(
            pngBytes = png,
            width = options.outputSize,
            height = options.outputSize,
            sourcePath = extracted.sourcePath,
        )
    }

    private fun ensureRecording(options: IconExtractOptions): IconRecording? {
        val existing = recording
        if (existing != null &&
            recordedDensityDpi == options.densityDpi &&
            recordedSdk == options.sdkVersion &&
            recordedPreferAdaptive == options.preferAdaptive
        ) {
            return existing
        }
        extractor.updateOptions(options.copy(mask = IconMask.NONE))
        val rec = extractor.record() ?: return null
        storeRecording(rec, options)
        return rec
    }

    private fun storeRecording(rec: IconRecording, options: IconExtractOptions) {
        recording = rec
        recordedDensityDpi = options.densityDpi
        recordedSdk = options.sdkVersion
        recordedPreferAdaptive = options.preferAdaptive
    }

    private fun checkOpen() {
        check(!closed) { "IconSession is closed" }
    }

    override fun close() {
        if (closed) return
        closed = true
        recording = null
        if (ownsData) data.close()
    }

    companion object {
        fun open(apkPath: String): IconSession? {
            val opened = openBinaryData(apkPath) ?: return null
            return try {
                val normalized = normalizeApkData(opened)
                when {
                    normalized == null -> {
                        opened.close()
                        null
                    }
                    normalized !== opened -> {
                        opened.close()
                        IconSession(normalized, ownsData = true)
                    }
                    else -> IconSession(opened, ownsData = true)
                }
            } catch (t: Throwable) {
                opened.close()
                throw t
            }
        }

        fun open(apkBytes: ByteArray): IconSession? {
            val normalized = normalizeApkData(HeapBinaryData(apkBytes)) ?: return null
            return IconSession(normalized, ownsData = true)
        }

        private fun normalizeApkData(data: BinaryData): BinaryData? {
            if (data.size >= 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) {
                return data
            }
            if (data.size >= 4 && data[0] == 'R'.code.toByte() && data[1] == 'I'.code.toByte()) {
                val bytes = data.asHeapArrayOrNull() ?: data.copyOfRange(0, data.size)
                val apk = ApksExtractor.extractBaseApkBytes(bytes) ?: return null
                return HeapBinaryData(apk)
            }
            return data
        }
    }
}

/** Public opaque handle confirming a recording exists (no internal types leak). */
data class IconRecordingHandle(
    val sourcePath: String?,
    val iconRef: String?,
)
