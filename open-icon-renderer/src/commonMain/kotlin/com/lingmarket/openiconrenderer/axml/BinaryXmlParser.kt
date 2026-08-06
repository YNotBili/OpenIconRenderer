package com.lingmarket.openiconrenderer.axml

import com.lingmarket.openiconrenderer.util.ByteReader
import com.lingmarket.openiconrenderer.util.ANDROID_NS

internal data class XmlAttribute(
    val namespace: String?,
    val name: String,
    val rawValue: String?,
    val typedValue: TypedValue?,
)

internal data class TypedValue(
    val type: Int,
    val data: Int,
) {
    fun asString(pool: StringPool): String? =
        when (type) {
            AxmlTypes.TYPE_STRING -> pool.get(data)
            AxmlTypes.TYPE_INT_DEC, AxmlTypes.TYPE_INT_HEX -> data.toString()
            AxmlTypes.TYPE_REFERENCE -> "@${data.toUInt().toString(16).padStart(8, '0')}"
            else -> null
        }
}

internal object AxmlTypes {
    const val TYPE_STRING = 0x03
    const val TYPE_REFERENCE = 0x01
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
}

internal data class XmlNode(
    val tag: String,
    val namespace: String?,
    val attributes: List<XmlAttribute>,
    val children: List<XmlNode>,
)

internal class StringPool(private val strings: List<String>) {
    fun get(index: Int): String? = strings.getOrNull(index)
}

internal class BinaryXmlParser(private val data: ByteArray) {
    private lateinit var stringPool: StringPool
    private var resourceMap: IntArray = intArrayOf()

    fun parse(): XmlNode? {
        val reader = ByteReader(data)
        val rootType = reader.readU16LE()
        reader.readU16LE() // headerSize
        reader.readU32LE() // chunkSize
        require(rootType == RES_XML_TYPE) { "Not an XML chunk" }
        var root: XmlNode? = null
        val stack = ArrayDeque<MutableNode>()
        while (reader.hasRemaining()) {
            val chunkStart = reader.position
            val type = reader.readU16LE()
            val headerSize = reader.readU16LE()
            val chunkSize = reader.readU32LE()
            when (type) {
                RES_STRING_POOL_TYPE -> stringPool = parseStringPool(reader, headerSize, chunkSize)
                RES_XML_RESOURCE_MAP_TYPE -> {
                    val count = (chunkSize - headerSize) / 4
                    resourceMap = IntArray(count) { reader.readU32LE() }
                }
                RES_XML_START_NAMESPACE_TYPE -> reader.seek(chunkStart + chunkSize)
                RES_XML_END_NAMESPACE_TYPE -> reader.seek(chunkStart + chunkSize)
                RES_XML_START_ELEMENT_TYPE -> {
                    reader.skip(8) // line, comment
                    val ns = stringPool.get(reader.readS32LE())
                    val name = stringPool.get(reader.readS32LE()) ?: ""
                    reader.readU16LE() // attributeStart
                    reader.readU16LE() // attributeSize
                    val attributeCount = reader.readU16LE()
                    reader.readU16LE() // idIndex
                    reader.readU16LE() // classIndex
                    reader.readU16LE() // styleIndex
                    val attrs = ArrayList<XmlAttribute>(attributeCount)
                    repeat(attributeCount) {
                        val attrNs = stringPool.get(reader.readS32LE())
                        val attrName = stringPool.get(reader.readS32LE()) ?: ""
                        val rawValueIdx = reader.readS32LE()
                        reader.readU16LE() // size
                        reader.readU8() // reserved
                        val dataType = reader.readU8()
                        val data = reader.readS32LE()
                        val rawValue = if (rawValueIdx >= 0) stringPool.get(rawValueIdx) else null
                        attrs.add(
                            XmlAttribute(
                                namespace = attrNs,
                                name = attrName,
                                rawValue = rawValue,
                                typedValue = TypedValue(dataType, data),
                            ),
                        )
                    }
                    val node = MutableNode(name, ns, attrs)
                    stack.lastOrNull()?.children?.add(node)
                    if (root == null && stack.isEmpty()) {
                        // keep reference through stack
                    }
                    stack.addLast(node)
                }
                RES_XML_END_ELEMENT_TYPE -> {
                    reader.seek(chunkStart + headerSize)
                    reader.skip(8)
                    reader.skip(4)
                    reader.skip(4)
                    if (stack.size == 1) {
                        root = stack.removeLast().toNode()
                    } else {
                        stack.removeLast()
                    }
                }
                RES_XML_CDATA_TYPE -> reader.seek(chunkStart + chunkSize)
                else -> reader.seek(chunkStart + chunkSize)
            }
            reader.seek(chunkStart + chunkSize)
        }
        return root ?: stack.singleOrNull()?.toNode()
    }

    private fun parseStringPool(reader: ByteReader, headerSize: Int, chunkSize: Int): StringPool {
        val start = reader.position - 8
        val stringCount = reader.readU32LE()
        val styleCount = reader.readU32LE()
        val flags = reader.readU32LE()
        val stringsStart = reader.readU32LE()
        val stylesStart = reader.readU32LE()
        val offsets = IntArray(stringCount) { reader.readU32LE() }
        repeat(styleCount) { reader.readU32LE() }
        val utf8 = flags and (1 shl 8) != 0
        val stringsBase = start + stringsStart
        val strings = List(stringCount) { index ->
            val offset = stringsBase + offsets[index]
            if (utf8) readUtf8String(offset) else readUtf16String(offset)
        }
        reader.seek(start + chunkSize)
        return StringPool(strings)
    }

    private fun readUtf8String(offset: Int): String {
        var pos = offset
        val reader = ByteReader(data, pos)
        val charLen = readUtf8Length(reader)
        val byteLen = readUtf8Length(reader)
        return reader.readBytes(byteLen).decodeToString()
    }

    private fun readUtf16String(offset: Int): String {
        val charLen = data[offset + 1].toInt() shl 8 or (data[offset].toInt() and 0xFF)
        val start = offset + if (charLen and 0x8000 != 0) 4 else 2
        val actualLen = if (charLen and 0x8000 != 0) {
            (data[offset + 3].toInt() shl 8 or (data[offset + 2].toInt() and 0xFF))
        } else {
            charLen
        }
        val chars = CharArray(actualLen)
        var i = 0
        var p = start
        while (i < actualLen) {
            val lo = data[p].toInt() and 0xFF
            val hi = data[p + 1].toInt() and 0xFF
            chars[i++] = ((hi shl 8) or lo).toChar()
            p += 2
        }
        return chars.concatToString()
    }

    private fun readUtf8Length(reader: ByteReader): Int {
        val first = reader.readU8()
        return if (first and 0x80 != 0) {
            ((first and 0x7F) shl 8) or reader.readU8()
        } else {
            first
        }
    }

    private class MutableNode(
        val tag: String,
        val namespace: String?,
        val attributes: List<XmlAttribute>,
        val children: MutableList<MutableNode> = mutableListOf(),
    ) {
        fun toNode(): XmlNode = XmlNode(tag, namespace, attributes, children.map { it.toNode() })
    }

    companion object {
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val RES_XML_START_NAMESPACE_TYPE = 0x0100
        private const val RES_XML_END_NAMESPACE_TYPE = 0x0101
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val RES_XML_CDATA_TYPE = 0x0104

        const val TYPE_STRING = AxmlTypes.TYPE_STRING
        const val TYPE_REFERENCE = AxmlTypes.TYPE_REFERENCE
        const val TYPE_INT_DEC = AxmlTypes.TYPE_INT_DEC
        const val TYPE_INT_HEX = AxmlTypes.TYPE_INT_HEX

        fun androidAttr(node: XmlNode, name: String): String? {
            for (attr in node.attributes) {
                if (attr.name == name || attr.namespace == ANDROID_NS && attr.name == name) {
                    return attr.rawValue ?: attr.typedValue?.let { null }
                }
            }
            for (attr in node.attributes) {
                if (attr.name.endsWith(name)) {
                    return attr.rawValue
                }
            }
            return null
        }

        fun androidAttrRef(node: XmlNode, name: String): Int? {
            for (attr in node.attributes) {
                if (attr.name == name || attr.namespace == ANDROID_NS && attr.name == name) {
                    val tv = attr.typedValue
                    if (tv != null && tv.type == TYPE_REFERENCE) return tv.data
                    attr.rawValue?.let { raw ->
                        if (raw.startsWith("@")) {
                            val hex = raw.removePrefix("@").removePrefix("0x")
                            return hex.toIntOrNull(16)
                        }
                    }
                }
            }
            return null
        }
    }
}
