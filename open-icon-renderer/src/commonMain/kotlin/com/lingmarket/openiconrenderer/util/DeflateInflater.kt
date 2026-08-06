package com.lingmarket.openiconrenderer.util

/**
 * RFC 1951 raw DEFLATE inflater (ZIP method 8 / PNG IDAT).
 */
internal object DeflateInflater {
    fun inflate(input: ByteArray): ByteArray {
        val reader = BitReader(input)
        val output = ArrayList<Byte>(input.size * 4)
        var bfinal: Int
        do {
            val header = reader.readBits(3)
            bfinal = header and 1
            when ((header shr 1) and 3) {
                0 -> inflateStored(reader, output)
                1 -> inflateFixed(reader, output)
                2 -> inflateDynamic(reader, output)
                else -> error("Unsupported DEFLATE block type")
            }
        } while (bfinal == 0)
        return output.toByteArray()
    }

    private fun inflateStored(reader: BitReader, output: MutableList<Byte>) {
        reader.alignByte()
        val len = reader.readU16LE()
        val nlen = reader.readU16LE()
        require(len.inv() and 0xFFFF == nlen) { "Invalid stored block length" }
        repeat(len) { output.add(reader.readByte()) }
    }

    private fun inflateFixed(reader: BitReader, output: MutableList<Byte>) {
        val litLengths = IntArray(288) { if (it <= 143) 8 else if (it <= 255) 9 else if (it <= 279) 7 else 8 }
        val distLengths = IntArray(32) { 5 }
        inflateCodes(reader, output, HuffmanTree(litLengths), HuffmanTree(distLengths))
    }

    private fun inflateDynamic(reader: BitReader, output: MutableList<Byte>) {
        val hlit = reader.readBits(5) + 257
        val hdist = reader.readBits(5) + 1
        val hclen = reader.readBits(4) + 4
        val order = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        val clLengths = IntArray(19)
        repeat(hclen) { clLengths[order[it]] = reader.readBits(3) }
        val codeTree = HuffmanTree(clLengths)
        val lengths = IntArray(hlit + hdist)
        var i = 0
        while (i < lengths.size) {
            when (val sym = codeTree.decode(reader)) {
                in 0..15 -> lengths[i++] = sym
                16 -> repeat(reader.readBits(2) + 3) { lengths[i++] = lengths[i - 1] }
                17 -> repeat(reader.readBits(3) + 3) { lengths[i++] = 0 }
                18 -> repeat(reader.readBits(7) + 11) { lengths[i++] = 0 }
                else -> error("Bad code length symbol $sym")
            }
        }
        inflateCodes(
            reader,
            output,
            HuffmanTree(lengths.copyOfRange(0, hlit)),
            HuffmanTree(lengths.copyOfRange(hlit, lengths.size)),
        )
    }

    private fun inflateCodes(
        reader: BitReader,
        output: MutableList<Byte>,
        litTree: HuffmanTree,
        distTree: HuffmanTree,
    ) {
        while (true) {
            when (val sym = litTree.decode(reader)) {
                in 0..255 -> output.add(sym.toByte())
                256 -> return
                else -> {
                    val lengthIdx = sym - 257
                    val length = LENGTH_BASE[lengthIdx] + reader.readBits(LENGTH_EXTRA[lengthIdx])
                    val distSym = distTree.decode(reader)
                    val distance = DIST_BASE[distSym] + reader.readBits(DIST_EXTRA[distSym])
                    val start = output.size - distance
                    repeat(length) { j -> output.add(output[start + j]) }
                }
            }
        }
    }

    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0
        private var bitBuffer = 0
        private var bitCount = 0

        fun hasMore(): Boolean = bytePos < data.size || bitCount > 0

        fun alignByte() {
            bitBuffer = 0
            bitCount = 0
        }

        fun readBits(count: Int): Int {
            while (bitCount < count) {
                require(bytePos < data.size) { "Unexpected EOF in DEFLATE stream" }
                bitBuffer = bitBuffer or ((data[bytePos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            val mask = (1 shl count) - 1
            val value = bitBuffer and mask
            bitBuffer = bitBuffer ushr count
            bitCount -= count
            return value
        }

        fun readU16LE(): Int = readBits(8) or (readBits(8) shl 8)

        fun readByte(): Byte {
            alignByte()
            require(bytePos < data.size) { "Unexpected EOF" }
            return data[bytePos++]
        }
    }

    private class HuffmanTree(lengths: IntArray) {
        private val maxBits: Int
        private val symbols: IntArray
        private val counts: IntArray

        init {
            val blCount = IntArray(16)
            var max = 0
            for (len in lengths) {
                if (len > max) max = len
                if (len in 1..15) blCount[len]++
            }
            maxBits = max
            counts = IntArray(maxBits + 1)
            for (len in 1..maxBits) counts[len] = blCount[len]

            val nextCode = IntArray(16)
            var code = 0
            blCount[0] = 0
            for (bits in 1..15) {
                code = (code + blCount[bits - 1]) shl 1
                nextCode[bits] = code
            }

            val totalSymbols = blCount.sum()
            symbols = IntArray(totalSymbols)
            val codeCursor = nextCode.copyOf()
            for (symbol in lengths.indices) {
                val len = lengths[symbol]
                if (len == 0) continue
                val pos = codeCursor[len]++
                symbols[pos] = symbol
            }
        }

        fun decode(reader: BitReader): Int {
            var code = 0
            var first = 0
            var index = 0
            for (len in 1..maxBits) {
                code = code or reader.readBits(1)
                val count = counts[len]
                if (code - first < count) return symbols[index + (code - first)]
                index += count
                first = (first + count) shl 1
                code = code shl 1
            }
            error("Invalid Huffman code")
        }
    }

    private val LENGTH_BASE = intArrayOf(
        3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258,
    )
    private val LENGTH_EXTRA = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0,
    )
    private val DIST_BASE = intArrayOf(
        1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769,
        1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577,
    )
    private val DIST_EXTRA = intArrayOf(
        0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8,
        9, 9, 10, 10, 11, 11, 12, 12, 13, 13,
    )
}
