package com.lingmarket.openiconrenderer.zip

import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.inflateRawDeflate
import com.lingmarket.openiconrenderer.util.u16LE
import com.lingmarket.openiconrenderer.util.u32LE

internal class ZipEntry internal constructor(
    val path: String,
    val compressionMethod: Int,
    val compressedSize: Int,
    val uncompressedSize: Int,
    val localHeaderOffset: Int,
)

/**
 * View into a ZIP entry payload. Store entries may reference a mapped APK without copying
 * (e.g. large resources.arsc).
 */
internal class ZipMappedBytes(
    val data: BinaryData,
    val offset: Int,
    val length: Int,
)

internal class ZipArchive(private val data: BinaryData) {
    constructor(bytes: ByteArray) : this(HeapBinaryData(bytes))

    private val entries: Map<String, ZipEntry>
    val entryNames: Set<String>

    init {
        val list = parseCentralDirectory()
        entries = list.associateBy { it.path }
        entryNames = entries.keys
    }

    fun contains(path: String): Boolean = entries.containsKey(path)

    fun uncompressedSize(path: String): Int = entries[path]?.uncompressedSize ?: 0

    fun readEntry(path: String): ByteArray? {
        val entry = entries[path] ?: return null
        val payload = payloadRange(entry) ?: return null
        return when (entry.compressionMethod) {
            0 -> data.copyOfRange(payload.first, payload.second)
            8 -> inflateRawDeflate(
                data.copyOfRange(payload.first, payload.second),
                entry.uncompressedSize,
            )
            else -> null
        }
    }

    /**
     * Prefer zero-copy for store entries (including large resources.arsc). ARSC indexing
     * uses a windowed [BinaryData] reader and does not require a heap copy of the table.
     */
    fun mapEntry(path: String): ZipMappedBytes? {
        val entry = entries[path] ?: return null
        val payload = payloadRange(entry) ?: return null
        return when (entry.compressionMethod) {
            0 -> ZipMappedBytes(data, payload.first, entry.compressedSize)
            8 -> {
                val inflated = inflateRawDeflate(
                    data.copyOfRange(payload.first, payload.second),
                    entry.uncompressedSize,
                )
                ZipMappedBytes(HeapBinaryData(inflated), 0, inflated.size)
            }
            else -> null
        }
    }

    private fun payloadRange(entry: ZipEntry): Pair<Int, Int>? {
        val localOffset = entry.localHeaderOffset
        if (localOffset + 30 > data.size) return null
        val nameLen = data.u16LE(localOffset + 26)
        val extraLen = data.u16LE(localOffset + 28)
        val dataOffset = localOffset + 30 + nameLen + extraLen
        val end = dataOffset + entry.compressedSize
        if (dataOffset < 0 || end > data.size) return null
        return dataOffset to end
    }

    private fun parseCentralDirectory(): List<ZipEntry> {
        val eocdOffset = findEndOfCentralDirectory()
        require(eocdOffset >= 0) { "ZIP EOCD not found" }
        val totalEntries = data.u16LE(eocdOffset + 10)
        val cdSize = data.u32LE(eocdOffset + 12)
        val cdOffset = data.u32LE(eocdOffset + 16)
        val cdEnd = (cdOffset.toLong() + cdSize.toLong()).coerceAtMost(data.size.toLong()).toInt()
        require(cdOffset >= 0 && cdEnd >= cdOffset) { "Invalid central directory range" }
        // Heap: parse ByteArray directly (hot). Mapped: one memcpy of the CD, then heap parse.
        val heap = data.asHeapArrayOrNull()
        val heapCd: ByteArray
        val base: Int
        if (heap != null) {
            heapCd = heap
            base = cdOffset
        } else {
            heapCd = data.copyOfRange(cdOffset, cdEnd)
            base = 0
        }
        val cdLimit = if (base == 0) heapCd.size else cdEnd
        return parseCdEntries(heapCd, base, cdLimit, totalEntries)
    }

    private fun parseCdEntries(
        cd: ByteArray,
        base: Int,
        cdLimit: Int,
        totalEntries: Int,
    ): List<ZipEntry> {
        val result = ArrayList<ZipEntry>(minOf(totalEntries, 4096))
        var offset = base
        repeat(totalEntries) {
            if (offset + 46 > cdLimit) return@repeat
            require(cd.u32LE(offset) == CEN_SIG) { "Invalid central directory entry" }
            val compression = cd.u16LE(offset + 10)
            val compressedSize = cd.u32LE(offset + 20)
            val uncompressedSize = cd.u32LE(offset + 24)
            val nameLen = cd.u16LE(offset + 28)
            val extraLen = cd.u16LE(offset + 30)
            val commentLen = cd.u16LE(offset + 32)
            val localOffset = cd.u32LE(offset + 42)
            val nameStart = offset + 46
            val nameEnd = nameStart + nameLen
            offset = nameEnd + extraLen + commentLen
            if (nameEnd > cdLimit) return@repeat
            if (!isInterestingEntryBytes(cd, nameStart, nameLen)) return@repeat
            val path = cd.decodeToString(nameStart, nameEnd)
            if (path.endsWith("/")) return@repeat
            result.add(
                ZipEntry(
                    path = path,
                    compressionMethod = compression,
                    compressedSize = compressedSize,
                    uncompressedSize = uncompressedSize,
                    localHeaderOffset = localOffset,
                ),
            )
        }
        return result
    }

    private fun findEndOfCentralDirectory(): Int {
        val window = minOf(65557, data.size)
        val base = data.size - window
        // Mapped: copy ≤64KiB window. Heap: scan in place.
        val heap = data.asHeapArrayOrNull()
        if (heap != null) {
            var i = heap.size - 22
            val minOffset = base
            while (i >= minOffset) {
                if (heap[i] == 0x50.toByte() &&
                    heap[i + 1] == 0x4B.toByte() &&
                    heap[i + 2] == 0x05.toByte() &&
                    heap[i + 3] == 0x06.toByte()
                ) {
                    return i
                }
                i--
            }
            return -1
        }
        val buf = data.copyOfRange(base, data.size)
        var i = buf.size - 22
        while (i >= 0) {
            if (buf[i] == 0x50.toByte() &&
                buf[i + 1] == 0x4B.toByte() &&
                buf[i + 2] == 0x05.toByte() &&
                buf[i + 3] == 0x06.toByte()
            ) {
                return base + i
            }
            i--
        }
        return -1
    }

    companion object {
        private const val CEN_SIG = 0x02014b50

        private fun isInterestingEntryBytes(data: ByteArray, nameStart: Int, nameLen: Int): Boolean {
            if (nameLen <= 0) return false
            if (nameLen == 19 && matchAscii(data, nameStart, "AndroidManifest.xml")) return true
            if (nameLen == 14 && matchAscii(data, nameStart, "resources.arsc")) return true
            if (nameLen >= 4 && data[nameStart] == 'r'.code.toByte() && data[nameStart + 1] == 'e'.code.toByte() &&
                data[nameStart + 2] == 's'.code.toByte() && data[nameStart + 3] == '/'.code.toByte()
            ) {
                return true
            }
            if (nameLen >= 2 && data[nameStart] == 'r'.code.toByte() && data[nameStart + 1] == '/'.code.toByte()) {
                return true
            }
            if (nameLen >= 7 && matchAscii(data, nameStart, "assets/")) return true
            // Native ABI folders (`lib/arm64-v8a/...`) — needed by ApkMetadataParser.
            if (nameLen >= 4 && matchAscii(data, nameStart, "lib/")) return true
            return false
        }

        private fun matchAscii(data: ByteArray, start: Int, ascii: String): Boolean {
            for (i in ascii.indices) {
                if (data[start + i] != ascii[i].code.toByte()) return false
            }
            return true
        }
    }
}
