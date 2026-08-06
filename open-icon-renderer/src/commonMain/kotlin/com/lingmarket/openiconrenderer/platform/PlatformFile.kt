package com.lingmarket.openiconrenderer.platform

import com.lingmarket.openiconrenderer.util.BinaryData

internal expect fun readFileBytes(path: String): ByteArray?

/** Open APK/ZIP for random access; prefers mmap on native/JVM. Caller must [BinaryData.close]. */
internal expect fun openBinaryData(path: String): BinaryData?
