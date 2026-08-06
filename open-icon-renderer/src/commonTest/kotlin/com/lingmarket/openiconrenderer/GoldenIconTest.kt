package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.png.PngEncoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GoldenIconTest {
    @Test
    fun solidColorPngProducesDeterministicOutput() {
        val pngA = redSquarePng(32, 32)
        val pngB = redSquarePng(32, 32)
        assertTrue(pngA.contentEquals(pngB))
    }

    @Test
    fun minimalApkProducesStableIconDimensions() {
        val apk = TestApkBuilder()
            .addRasterIcon("res/mipmap-xxhdpi/ic_launcher.png", redSquarePng(64, 64))
            .build()
        val first = OpenIconRenderer.extractLauncherIcon(apk, IconExtractOptions(outputSize = 64))
        val second = OpenIconRenderer.extractLauncherIcon(apk, IconExtractOptions(outputSize = 64))
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first.pngBytes.size, second.pngBytes.size)
        assertTrue(first.pngBytes.contentEquals(second.pngBytes))
    }
}
