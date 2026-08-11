package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkMetadataParseTest {
    @Test
    fun parseBiliTerminalReleaseApk() {
        val apk = "/home/rj/BiliTerminal/app/build/outputs/apk/release/app-release.apk"
        val preview = OpenIconRenderer.parseApkPreview(apk)
        assertNotNull(preview, "parseApkPreview returned null")
        val m = preview.metadata
        println("meta=$m iconBytes=${preview.iconPng?.size}")
        assertEquals("com.RobinNotBad.BiliClient", m.packageName)
        assertEquals("哔哩终端", m.applicationLabel)
        assertEquals(20260722, m.versionCode)
        assertEquals("3.0.4-Qx", m.versionName)
        assertEquals(14, m.minSdk)
        assertEquals(26, m.targetSdk)
        assertTrue(m.architectures.isNotEmpty(), "expected native ABIs, got ${m.architectures}")
        assertNotNull(preview.iconPng)
        assertTrue(preview.iconPng!!.isNotEmpty())
    }
}
