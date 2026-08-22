package com.lingmarket.openiconrenderer.api

/**
 * One Manifest `uses-permission` / `uses-permission-sdk-23` declaration.
 */
data class ApkPermission(
    val name: String,
    val maxSdkVersion: Int? = null,
    val required: Boolean = true,
)

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
    val permissions: List<ApkPermission> = emptyList(),
    /** Manifest launcher icon resource ref (`@7f…` / `@mipmap/…`), if present. */
    val launcherIconRef: String? = null,
)
