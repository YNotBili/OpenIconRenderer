package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.zip.ZipArchive

object ApksExtractor {
    fun extractBaseApkBytes(apksBytes: ByteArray): ByteArray? {
        val zip = ZipArchive(apksBytes)
        val apkEntries = zip.entryNames.filter { it.lowercase().endsWith(".apk") }
        if (apkEntries.isEmpty()) return null
        val scored = apkEntries.map { it to scoreApkEntry(it) }.sortedByDescending { it.second }
        val best = scored.firstOrNull()?.first ?: return null
        return zip.readEntry(best)
    }

    private fun scoreApkEntry(name: String): Int {
        val lower = name.lowercase()
        return when {
            "universal" in lower -> 100
            "base" in lower && "split" !in lower -> 90
            "base" in lower -> 80
            lower.endsWith("base.apk") -> 85
            else -> 10
        }
    }
}
