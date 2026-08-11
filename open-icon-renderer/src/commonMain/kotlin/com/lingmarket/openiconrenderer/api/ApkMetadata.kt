package com.lingmarket.openiconrenderer.api

/**
 * Lightweight APK/APKS identity from AndroidManifest.xml + lib/ ABIs.
 * Parsed entirely inside OpenIconRenderer (no aapt).
 */
data class ApkMetadata(
    val packageName: String,
    val applicationLabel: String?,
    val versionCode: Int,
    val versionName: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val architectures: List<String>,
)
