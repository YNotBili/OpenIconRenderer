package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import com.lingmarket.openiconrenderer.apk.ApkMetadataParser
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.TypedValue
import com.lingmarket.openiconrenderer.axml.XmlAttribute
import com.lingmarket.openiconrenderer.axml.XmlNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ApkMetadataParserTest {
    @Test
    fun parseManifestCollectsPermissionsAndSdk() {
        val root = XmlNode(
            tag = "manifest",
            namespace = null,
            attributes = listOf(
                stringAttr("package", "com.example.app"),
                intAttr("versionCode", 42),
                stringAttr("versionName", "1.2.3"),
            ),
            children = listOf(
                XmlNode(
                    tag = "uses-sdk",
                    namespace = null,
                    attributes = listOf(
                        intAttr("minSdkVersion", 26),
                        intAttr("targetSdkVersion", 34),
                    ),
                    children = emptyList(),
                ),
                XmlNode(
                    tag = "uses-permission",
                    namespace = null,
                    attributes = listOf(stringAttr("name", "android.permission.INTERNET")),
                    children = emptyList(),
                ),
                XmlNode(
                    tag = "uses-permission",
                    namespace = null,
                    attributes = listOf(
                        stringAttr("name", "android.permission.CAMERA"),
                        intAttr("maxSdkVersion", 32),
                        boolAttr("required", false),
                    ),
                    children = emptyList(),
                ),
                XmlNode(
                    tag = "uses-permission-sdk-23",
                    namespace = null,
                    attributes = listOf(stringAttr("name", "android.permission.ACCESS_FINE_LOCATION")),
                    children = emptyList(),
                ),
                XmlNode(
                    tag = "uses-permission",
                    namespace = null,
                    attributes = listOf(stringAttr("name", "android.permission.INTERNET")),
                    children = emptyList(),
                ),
                XmlNode(
                    tag = "application",
                    namespace = null,
                    attributes = listOf(stringAttr("label", "Example")),
                    children = emptyList(),
                ),
            ),
        )

        val meta = ApkMetadataParser.parseManifest(root, listOf("arm64-v8a", "armeabi-v7a"))
        assertNotNull(meta)
        assertEquals("com.example.app", meta.packageName)
        assertEquals(42, meta.versionCode)
        assertEquals("1.2.3", meta.versionName)
        assertEquals("Example", meta.applicationLabel)
        assertEquals(26, meta.minSdk)
        assertEquals(34, meta.targetSdk)
        assertEquals(listOf("arm64-v8a", "armeabi-v7a"), meta.architectures)
        assertEquals(
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.CAMERA",
                "android.permission.INTERNET",
            ),
            meta.permissions.map { it.name },
        )
        val camera = meta.permissions.first { it.name.endsWith("CAMERA") }
        assertEquals(32, camera.maxSdkVersion)
        assertEquals(false, camera.required)
    }

    @Test
    fun parseApkPreviewOnRealApkIfPresent() {
        val candidates = listOf(
            "/home/alliehe/文档/Projects/LingMarket/LingMarket/app/build/outputs/apk/debug/app-debug.apk",
            "/home/alliehe/文档/Projects/LingMarket/LingMarket/app/build/outputs/apk/release/app-release.apk",
            "/home/alliehe/下载/老李社区 手表版_1.0.1_com.zxi2233.laoli_community_waer_.apk",
        )
        var preview: com.lingmarket.openiconrenderer.api.ApkPreview? = null
        var pathUsed = ""
        for (path in candidates) {
            val result = runCatching {
                OpenIconRenderer.parseApkPreview(path, IconExtractOptions(outputSize = 96))
            }.getOrNull()
            if (result != null) {
                preview = result
                pathUsed = path
                break
            }
        }
        if (preview == null) {
            println("[skip] no readable real APK for parseApkPreview smoke")
            return
        }
        val m = preview.metadata
        assertTrue(m.packageName.isNotBlank(), "packageName blank")
        assertTrue(m.versionCode >= 1, "versionCode=${m.versionCode}")
        println("apk=$pathUsed")
        println("package=${m.packageName}")
        println("version=${m.versionName} (${m.versionCode})")
        println("sdk=min=${m.minSdk} target=${m.targetSdk}")
        println("abis=${m.architectures}")
        println("label=${m.applicationLabel}")
        println("iconRef=${m.launcherIconRef}")
        println("permissions(${m.permissions.size})=${m.permissions.joinToString { it.name }}")
        println("iconPng=${preview.iconPng?.size ?: 0} bytes")
        assertNotNull(m.packageName)
        // Label may still be null for some APKs, but LingMarket debug should resolve @string.
        if (pathUsed.contains("app-debug.apk")) {
            assertTrue(
                !m.applicationLabel.isNullOrBlank(),
                "expected resolved applicationLabel for LingMarket debug APK, got ${m.applicationLabel}",
            )
        }
    }

    private fun stringAttr(name: String, value: String) = XmlAttribute(
        namespace = null,
        name = name,
        rawValue = value,
        typedValue = TypedValue(BinaryXmlParser.TYPE_STRING, 0),
    )

    private fun intAttr(name: String, value: Int) = XmlAttribute(
        namespace = null,
        name = name,
        rawValue = null,
        typedValue = TypedValue(BinaryXmlParser.TYPE_INT_DEC, value),
    )

    private fun boolAttr(name: String, value: Boolean) = XmlAttribute(
        namespace = null,
        name = name,
        rawValue = null,
        typedValue = TypedValue(0x12, if (value) 1 else 0),
    )
}
