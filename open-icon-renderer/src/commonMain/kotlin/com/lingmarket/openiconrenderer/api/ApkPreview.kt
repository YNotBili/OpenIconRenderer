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
 * Identity + SDK + ABI + permission metadata parsed from an APK/base APK.
 */
data class ApkMetadata(
    val packageName: String,
    val versionCode: Int,
    val versionName: String?,
    val applicationLabel: String?,
    val minSdk: Int?,
    val targetSdk: Int?,
    val architectures: List<String>,
    val permissions: List<ApkPermission>,
    /** Manifest launcher icon resource ref (`@7f…` / `@mipmap/…`), if present. */
    val launcherIconRef: String? = null,
)

/**
 * Combined metadata + optional launcher icon PNG from a single parse session.
 */
data class ApkPreview(
    val metadata: ApkMetadata,
    val iconPng: ByteArray?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as ApkPreview
        if (metadata != other.metadata) return false
        if (iconPng != null) {
            if (other.iconPng == null || !iconPng.contentEquals(other.iconPng)) return false
        } else if (other.iconPng != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = metadata.hashCode()
        result = 31 * result + (iconPng?.contentHashCode() ?: 0)
        return result
    }
}
