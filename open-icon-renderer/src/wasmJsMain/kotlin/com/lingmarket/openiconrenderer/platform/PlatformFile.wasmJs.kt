package com.lingmarket.openiconrenderer.platform

import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData

internal actual fun readFileBytes(path: String): ByteArray? = null

internal actual fun openBinaryData(path: String): BinaryData? =
    readFileBytes(path)?.let { HeapBinaryData(it) }
