package com.lingmarket.openiconrenderer.api

/** Combined metadata + optional launcher icon PNG from a single APK open. */
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
