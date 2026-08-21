package com.lingmarket.openiconrenderer.arsc

import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.BinaryDataReader
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.u16LE
import com.lingmarket.openiconrenderer.util.u32LE

internal sealed class ResolvedResource {
    data class FilePath(val path: String) : ResolvedResource()
    data class Color(val argb: Int) : ResolvedResource()
    data class Reference(val resId: Int) : ResolvedResource()
}

/**
 * Lazy resources.arsc: indexes TYPE chunk locations at construction, resolves individual
 * resource IDs on demand, and decodes global strings only when referenced.
 *
 * Accepts [BinaryData] so mmap-backed ZIP store entries (large ARSC) are not copied
 * wholesale onto the heap — only string offsets (~0.7MB) and a windowed scan.
 */
internal class ResourceTable(
    private val data: BinaryData,
    private val base: Int = 0,
    private val length: Int = data.size - base,
) {
    constructor(bytes: ByteArray, base: Int = 0, length: Int = bytes.size - base) :
        this(HeapBinaryData(bytes), base, length)

    private val heap: ByteArray? = data.asHeapArrayOrNull()
    private val stringUtf8: Boolean
    private val stringCount: Int
    private val stringDataBase: Int
    private val stringOffsets: IntArray
    private val stringCache = HashMap<Int, String>(8)
    private val typeChunks = HashMap<Int, ArrayList<TypeChunk>>()

    init {
        require(base >= 0 && length >= 0 && base + length <= data.size)
        val reader = BinaryDataReader(data, base, base + length)
        check(reader.readU16LE() == RES_TABLE_TYPE) { "Not a resource table" }
        reader.readU16LE()
        val tableSize = reader.readU32LE()
        reader.readU32LE() // packageCount

        val poolStart = reader.position
        // poolSize lives at poolStart+4; read via reader after seeking past type/headerSize
        reader.seek(poolStart + 4)
        val poolSize = reader.readU32LE()
        stringCount = reader.readU32LE()
        reader.readU32LE() // styleCount
        val flags = reader.readU32LE()
        val stringsStart = reader.readU32LE()
        reader.readU32LE() // stylesStart
        stringUtf8 = flags and (1 shl 8) != 0
        // One memcpy of the offset table (~0.7MB on Fenix) beats windowed u32 loops.
        val offsetsBytes = if (stringCount > 0) {
            data.copyOfRange(reader.position, reader.position + stringCount * 4)
        } else {
            ByteArray(0)
        }
        stringOffsets = IntArray(stringCount) { i -> offsetsBytes.u32LE(i * 4) }
        reader.seek(reader.position + stringCount * 4)
        stringDataBase = poolStart + stringsStart
        // Skip string pool body without paging it in.
        reader.seek(poolStart + poolSize)

        val tableEnd = base + minOf(tableSize, length)
        while (reader.position < tableEnd) {
            val chunkStart = reader.position
            val chunkType = reader.readU16LE()
            reader.readU16LE()
            val chunkSize = reader.readU32LE()
            if (chunkType == RES_TABLE_PACKAGE_TYPE) {
                indexPackage(reader, chunkStart, chunkSize)
            }
            reader.seek(chunkStart + chunkSize)
        }
    }

    fun resolveReference(resId: Int): List<ResolvedResource> = resolveReference(resId, mutableSetOf())

    /**
     * Resolve a string/label resource id to display text.
     * Drawable/file paths are ignored when a plain string value is available.
     */
    fun resolveString(resId: Int): String? {
        val values = resolveReference(resId)
        val texts = values.mapNotNull { (it as? ResolvedResource.FilePath)?.path?.takeIf { p -> p.isNotBlank() } }
        return texts.firstOrNull { candidate ->
            val lower = candidate.lowercase()
            '/' !in candidate &&
                !lower.endsWith(".xml") &&
                !lower.endsWith(".png") &&
                !lower.endsWith(".webp") &&
                !lower.endsWith(".jpg") &&
                !lower.startsWith("res")
        } ?: texts.firstOrNull()
    }

    private fun resolveReference(resId: Int, visited: MutableSet<Int>): List<ResolvedResource> {
        if (!visited.add(resId)) return emptyList()
        val packageId = (resId ushr 24) and 0xFF
        val typeId = (resId ushr 16) and 0xFF
        val entryIndex = resId and 0xFFFF
        val chunks = typeChunks[typeId] ?: return emptyList()
        val out = ArrayList<ResolvedResource>(chunks.size)
        for (chunk in chunks) {
            if (chunk.packageId != packageId) continue
            if (entryIndex >= chunk.entryCount) continue
            val offset = u32At(chunk.offsetsPos + entryIndex * 4)
            if (offset == NO_ENTRY) continue
            readEntryValues(chunk.chunkStart + chunk.entriesStart + offset, out)
        }
        return out.flatMap { value ->
            when (value) {
                is ResolvedResource.Reference -> resolveReference(value.resId, visited)
                else -> listOf(value)
            }
        }
    }

    fun findColorByName(name: String): Int? = null

    private fun indexPackage(reader: BinaryDataReader, chunkStart: Int, chunkSize: Int) {
        val packageId = reader.readU32LE()
        // Skip name[128 utf16] + typeStrings + lastPublicType + keyStrings + lastPublicKey (+ typeIdOffset)
        // ResTable_package header is 288 bytes from chunkStart (incl. chunk header 8 + id 4 + name 256 + 5*4)
        reader.seek(chunkStart + 288)
        val packageEnd = chunkStart + chunkSize
        while (reader.position + 8 <= packageEnd) {
            val innerStart = reader.position
            val innerType = reader.readU16LE()
            reader.readU16LE()
            val innerSize = reader.readU32LE()
            if (innerSize < 8 || innerStart + innerSize > packageEnd) break
            if (innerType == RES_TABLE_TYPE_TYPE) {
                indexTypeChunk(reader, innerStart, packageId)
            }
            reader.seek(innerStart + innerSize)
        }
    }

    private fun indexTypeChunk(reader: BinaryDataReader, chunkStart: Int, packageId: Int) {
        val typeId = reader.readU8()
        reader.skip(3)
        val entryCount = reader.readU32LE()
        val entriesStart = reader.readU32LE()
        val configSize = reader.readU32LE()
        if (configSize < 4) return
        reader.skip(configSize - 4)
        val offsetsPos = reader.position
        typeChunks.getOrPut(typeId) { ArrayList(4) }.add(
            TypeChunk(
                packageId = packageId,
                chunkStart = chunkStart,
                entryCount = entryCount,
                entriesStart = entriesStart,
                offsetsPos = offsetsPos,
            ),
        )
    }

    private fun readEntryValues(entryPos: Int, out: MutableList<ResolvedResource>) {
        if (entryPos + 8 > base + length) return
        val flags = u16At(entryPos + 2)
        var pos = entryPos + 8 // size(2)+flags(2)+key(4)
        if ((flags and FLAG_COMPLEX) == 0) {
            addResolved(readResValueAt(pos), out)
        } else {
            if (pos + 8 > base + length) return
            pos += 4 // parent
            val mapCount = u32At(pos)
            pos += 4
            repeat(mapCount) {
                if (pos + 4 + 8 > base + length) return
                pos += 4 // name
                addResolved(readResValueAt(pos), out)
                pos += 8
            }
        }
    }

    private fun addResolved(value: ResValue, out: MutableList<ResolvedResource>) {
        val resolved = when (value.dataType) {
            TYPE_REFERENCE -> ResolvedResource.Reference(value.data)
            TYPE_STRING -> getString(value.data)?.let { ResolvedResource.FilePath(it) }
            TYPE_INT_COLOR -> ResolvedResource.Color(value.data)
            else -> null
        } ?: return
        out.add(resolved)
    }

    private fun readResValueAt(pos: Int): ResValue {
        val dataType = u8At(pos + 3)
        val valueData = u32At(pos + 4)
        return ResValue(dataType, valueData)
    }

    private fun getString(index: Int): String? {
        if (index < 0 || index >= stringCount) return null
        stringCache[index]?.let { return it }
        val s = readStringAt(stringDataBase + stringOffsets[index], stringUtf8)
        stringCache[index] = s
        return s
    }

    private fun readStringAt(offset: Int, utf8: Boolean): String {
        if (offset < 0 || offset >= data.size) return ""
        if (utf8) {
            var pos = offset
            pos += if ((u8At(pos) and 0x80) != 0) 2 else 1
            val first = u8At(pos)
            val byteLen: Int
            if (first and 0x80 != 0) {
                byteLen = ((first and 0x7F) shl 8) or u8At(pos + 1)
                pos += 2
            } else {
                byteLen = first
                pos += 1
            }
            if (pos + byteLen > data.size) return ""
            return data.decodeToString(pos, pos + byteLen)
        }
        if (offset + 2 > data.size) return ""
        val charLen = u16At(offset)
        val start = offset + if (charLen and 0x8000 != 0) 4 else 2
        val actualLen = if (charLen and 0x8000 != 0) u16At(offset + 2) else charLen
        if (start + actualLen * 2 > data.size) return ""
        val chars = CharArray(actualLen)
        var p = start
        for (i in 0 until actualLen) {
            chars[i] = u16At(p).toChar()
            p += 2
        }
        return chars.concatToString()
    }

    private fun u8At(index: Int): Int =
        if (heap != null) heap[index].toInt() and 0xFF
        else data[index].toInt() and 0xFF

    private fun u16At(index: Int): Int =
        if (heap != null) heap.u16LE(index) else data.u16LE(index)

    private fun u32At(index: Int): Int =
        if (heap != null) heap.u32LE(index) else data.u32LE(index)

    private data class TypeChunk(
        val packageId: Int,
        val chunkStart: Int,
        val entryCount: Int,
        val entriesStart: Int,
        val offsetsPos: Int,
    )

    private data class ResValue(val dataType: Int, val data: Int)

    companion object {
        private const val RES_TABLE_TYPE = 0x0002
        private const val RES_TABLE_PACKAGE_TYPE = 0x0200
        private const val RES_TABLE_TYPE_TYPE = 0x0201
        private const val TYPE_REFERENCE = 0x01
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_COLOR = 0x1C
        private const val NO_ENTRY = -1
        private const val FLAG_COMPLEX = 0x0001
    }
}
