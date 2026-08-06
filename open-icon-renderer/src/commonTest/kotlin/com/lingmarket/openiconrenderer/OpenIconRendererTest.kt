package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.png.PngEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OpenIconRendererTest {
    @Test
    fun extractsRasterLauncherIconFromMinimalApk() {
        val apk = TestApkBuilder()
            .addRasterIcon("res/mipmap-xxxhdpi-v4/ic_launcher.png", redSquarePng(48, 48))
            .build()
        val result = OpenIconRenderer.extractLauncherIcon(apk, IconExtractOptions(outputSize = 48))
        assertNotNull(result)
        assertEquals(48, result.width)
        assertEquals(48, result.height)
        assertTrue(result.pngBytes.isNotEmpty())
    }

    @Test
    fun pngRoundTripPreservesSize() {
        val png = redSquarePng(16, 16)
        val decoded = OpenIconRenderer.extractLauncherIcon(
            TestApkBuilder()
                .addRasterIcon("res/mipmap-mdpi/ic_launcher.png", png)
                .build(),
            IconExtractOptions(outputSize = 16),
        )
        assertNotNull(decoded)
        assertEquals(16, decoded.width)
    }
}

internal class TestApkBuilder {
    private val entries = linkedMapOf<String, ByteArray>()

    fun addRasterIcon(path: String, pngBytes: ByteArray): TestApkBuilder {
        entries[path] = pngBytes
        return this
    }

    fun build(): ByteArray = ZipWriter.write(entries)
}

internal object ZipWriter {
    fun write(entries: Map<String, ByteArray>): ByteArray {
        val localParts = ArrayList<ByteArray>()
        val centralParts = ArrayList<ByteArray>()
        var offset = 0
        for ((path, data) in entries) {
            val nameBytes = path.encodeToByteArray()
            val local = ByteArray(30 + nameBytes.size + data.size)
            writeU32(local, 0, 0x04034b50)
            writeU32(local, 18, data.size)
            writeU32(local, 22, data.size)
            writeU16(local, 26, nameBytes.size)
            nameBytes.copyInto(local, 30)
            data.copyInto(local, 30 + nameBytes.size)
            localParts.add(local)
            val central = ByteArray(46 + nameBytes.size)
            writeU32(central, 0, 0x02014b50)
            writeU32(central, 20, data.size)
            writeU32(central, 24, data.size)
            writeU16(central, 28, nameBytes.size)
            writeU32(central, 42, offset)
            nameBytes.copyInto(central, 46)
            centralParts.add(central)
            offset += local.size
        }
        val centralDir = centralParts.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        val eocd = ByteArray(22)
        writeU32(eocd, 0, 0x06054b50)
        writeU16(eocd, 8, entries.size)
        writeU16(eocd, 10, entries.size)
        writeU32(eocd, 12, centralDir.size)
        writeU32(eocd, 16, offset)
        return localParts.fold(ByteArray(0)) { acc, bytes -> acc + bytes } + centralDir + eocd
    }

    private fun writeU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}

internal fun redSquarePng(width: Int, height: Int): ByteArray {
    val pixels = IntArray(width * height) { 0xFFFF0000.toInt() }
    return PngEncoder.encode(RgbaBitmap(width, height, pixels))
}
