package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.apk.ApkMetadataParser
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.TypedValue
import com.lingmarket.openiconrenderer.axml.XmlAttribute
import com.lingmarket.openiconrenderer.axml.XmlNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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
                    attributes = listOf(
                        stringAttr("label", "Example"),
                        stringAttr("icon", "@mipmap/ic_launcher"),
                    ),
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
        assertEquals("@mipmap/ic_launcher", meta.launcherIconRef)
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
        typedValue = TypedValue(BinaryXmlParser.TYPE_INT_BOOLEAN, if (value) 1 else 0),
    )
}
