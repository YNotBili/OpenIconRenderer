package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.api.ApkMetadata
import com.lingmarket.openiconrenderer.arsc.ResourceTable
import com.lingmarket.openiconrenderer.arsc.resolveString
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.XmlAttribute
import com.lingmarket.openiconrenderer.axml.XmlNode
import com.lingmarket.openiconrenderer.zip.ZipArchive

/**
 * Read package / label / sdk / version from binary AndroidManifest.xml,
 * and native ABIs from `lib/<abi>/…` ZIP entries.
 */
internal class ApkMetadataParser(
    private val zip: ZipArchive,
    private val resources: ResourceTable?,
) {
    fun parse(): ApkMetadata? {
        val manifestBytes = zip.readEntry("AndroidManifest.xml") ?: return null
        val root = runCatching { BinaryXmlParser(manifestBytes).parse() }.getOrNull() ?: return null

        val packageName = attrString(root, "package")?.takeIf { it.isNotBlank() } ?: return null
        val versionCode = attrInt(root, "versionCode") ?: 1
        val versionName = attrString(root, "versionName")

        val application = findFirst(root, "application")
        val label = application?.let { resolveLabel(it) }

        val usesSdk = findFirst(root, "uses-sdk")
        val minSdk = usesSdk?.let { attrInt(it, "minSdkVersion") }
            ?: attrInt(root, "minSdkVersion")
        val targetSdk = usesSdk?.let { attrInt(it, "targetSdkVersion") }
            ?: attrInt(root, "targetSdkVersion")
            ?: minSdk

        return ApkMetadata(
            packageName = packageName,
            applicationLabel = label,
            versionCode = versionCode,
            versionName = versionName,
            minSdk = minSdk,
            targetSdk = targetSdk,
            architectures = listAbis(zip),
        )
    }

    private fun resolveLabel(application: XmlNode): String? {
        val raw = attrString(application, "label") ?: return null
        if (!raw.startsWith("@")) return raw.takeIf { it.isNotBlank() }
        val hex = raw.removePrefix("@").removePrefix("0x").removePrefix("0X")
        val resId = hex.toIntOrNull(16) ?: return null
        return resources?.resolveString(resId)?.takeIf { it.isNotBlank() }
    }

    private fun findFirst(node: XmlNode, tag: String): XmlNode? {
        if (node.tag == tag) return node
        for (child in node.children) {
            findFirst(child, tag)?.let { return it }
        }
        return null
    }

    private fun attrString(node: XmlNode, name: String): String? {
        for (attribute in matchingAttrs(node, name)) {
            val tv = attribute.typedValue
            if (tv != null) {
                when (tv.type) {
                    BinaryXmlParser.TYPE_REFERENCE ->
                        return "@${tv.data.toUInt().toString(16).padStart(8, '0')}"
                    BinaryXmlParser.TYPE_STRING ->
                        attribute.rawValue?.takeIf { it.isNotBlank() }?.let { return it }
                    BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX ->
                        return tv.data.toString()
                    else -> Unit
                }
            }
            attribute.rawValue?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun attrInt(node: XmlNode, name: String): Int? {
        for (attribute in matchingAttrs(node, name)) {
            val tv = attribute.typedValue
            if (tv != null) {
                when (tv.type) {
                    BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX -> return tv.data
                    BinaryXmlParser.TYPE_STRING -> {
                        val s = attribute.rawValue ?: continue
                        s.toIntOrNull()?.let { return it }
                    }
                    BinaryXmlParser.TYPE_REFERENCE -> {
                        // Rare: version as @integer/… — treat as unresolved.
                    }
                    else -> Unit
                }
            }
            attribute.rawValue?.toIntOrNull()?.let { return it }
        }
        return null
    }

    private fun matchingAttrs(node: XmlNode, name: String): Sequence<XmlAttribute> =
        node.attributes.asSequence().filter { attribute ->
            val attrName = attribute.name
            attrName == name ||
                attrName.endsWith(":$name") ||
                (attrName.endsWith(name) &&
                    (attrName.length == name.length ||
                        attrName[attrName.length - name.length - 1] == ':'))
        }

    companion object {
        private val KNOWN_ABIS = setOf(
            "armeabi-v7a", "armeabi", "arm64-v8a", "x86", "x86_64", "mips", "mips64",
        )

        fun listAbis(zip: ZipArchive): List<String> =
            zip.entryNames.asSequence()
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .mapNotNull { path -> path.split('/').getOrNull(1)?.takeIf { it in KNOWN_ABIS } }
                .distinct()
                .sorted()
                .toList()
    }
}
