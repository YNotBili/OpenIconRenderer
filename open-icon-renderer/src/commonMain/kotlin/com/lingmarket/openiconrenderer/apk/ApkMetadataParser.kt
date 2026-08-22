package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.api.ApkMetadata
import com.lingmarket.openiconrenderer.api.ApkPermission
import com.lingmarket.openiconrenderer.arsc.ResourceTable
import com.lingmarket.openiconrenderer.arsc.resolveString
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.XmlAttribute
import com.lingmarket.openiconrenderer.axml.XmlNode
import com.lingmarket.openiconrenderer.zip.ZipArchive

/**
 * Read package / label / sdk / version / uses-permission from binary AndroidManifest.xml,
 * and native ABIs from `lib/<abi>/…` ZIP entries.
 */
internal class ApkMetadataParser(
    private val zip: ZipArchive,
    private val resources: ResourceTable?,
) {
    fun parse(): ApkMetadata? {
        val manifestBytes = zip.readEntry("AndroidManifest.xml") ?: return null
        val root = runCatching { BinaryXmlParser(manifestBytes).parse() }.getOrNull() ?: return null
        return parseManifest(root, listAbis(zip), resources)
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

        internal fun parseManifest(
            root: XmlNode,
            architectures: List<String>,
            resources: ResourceTable? = null,
        ): ApkMetadata? {
            val packageName = attrString(root, "package")?.takeIf { it.isNotBlank() } ?: return null
            val versionCode = attrInt(root, "versionCode") ?: 1
            val versionName = attrString(root, "versionName")

            var minSdk: Int? = null
            var targetSdk: Int? = null
            var applicationLabel: String? = null
            var applicationIcon: String? = null
            var aliasIcon: String? = null
            val permissionByName = linkedMapOf<String, ApkPermission>()

            fun walk(node: XmlNode) {
                when (node.tag) {
                    "uses-sdk" -> {
                        minSdk = attrInt(node, "minSdkVersion") ?: minSdk
                        targetSdk = attrInt(node, "targetSdkVersion") ?: targetSdk
                    }
                    "application" -> {
                        if (applicationLabel == null) {
                            applicationLabel = resolveLabel(node, resources)
                        }
                        if (applicationIcon == null) {
                            applicationIcon = attrString(node, "icon")?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }
                    "activity-alias" -> {
                        val enabled = attrString(node, "enabled") ?: "true"
                        if (enabled != "false" && aliasIcon == null) {
                            aliasIcon = attrString(node, "icon")?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }
                    "uses-permission", "uses-permission-sdk-23" -> {
                        val name = attrString(node, "name")?.trim().orEmpty()
                        if (name.isNotEmpty() && name !in permissionByName) {
                            val required = attrString(node, "required")?.let { parseBool(it) } ?: true
                            permissionByName[name] = ApkPermission(
                                name = name,
                                maxSdkVersion = attrInt(node, "maxSdkVersion"),
                                required = required,
                            )
                        }
                    }
                }
                for (child in node.children) walk(child)
            }
            walk(root)

            minSdk = minSdk ?: attrInt(root, "minSdkVersion")
            targetSdk = targetSdk ?: attrInt(root, "targetSdkVersion") ?: minSdk

            return ApkMetadata(
                packageName = packageName,
                applicationLabel = applicationLabel,
                versionCode = versionCode.coerceAtLeast(1),
                versionName = versionName,
                minSdk = minSdk,
                targetSdk = targetSdk,
                architectures = architectures,
                permissions = permissionByName.values.sortedBy { it.name },
                launcherIconRef = applicationIcon ?: aliasIcon,
            )
        }

        private fun resolveLabel(application: XmlNode, resources: ResourceTable?): String? {
            val raw = attrString(application, "label") ?: return null
            if (!raw.startsWith("@")) return raw.takeIf { it.isNotBlank() }
            val hex = raw.removePrefix("@").removePrefix("0x").removePrefix("0X")
            val resId = hex.toIntOrNull(16) ?: return null
            return resources?.resolveString(resId)?.takeIf { it.isNotBlank() }
        }

        private fun attrString(node: XmlNode, name: String): String? {
            for (attribute in matchingAttrs(node, name)) {
                val tv = attribute.typedValue
                if (tv != null) {
                    when (tv.type) {
                        BinaryXmlParser.TYPE_REFERENCE ->
                            return attribute.rawValue?.takeIf { it.isNotBlank() }
                                ?: "@${tv.data.toUInt().toString(16).padStart(8, '0')}"
                        BinaryXmlParser.TYPE_STRING ->
                            attribute.rawValue?.takeIf { it.isNotBlank() }?.let { return it }
                        BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX ->
                            return tv.data.toString()
                        BinaryXmlParser.TYPE_INT_BOOLEAN ->
                            return if (tv.data != 0) "true" else "false"
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
                        BinaryXmlParser.TYPE_REFERENCE -> Unit
                        else -> Unit
                    }
                }
                attribute.rawValue?.toIntOrNull()?.let { return it }
            }
            return null
        }

        private fun parseBool(raw: String): Boolean =
            when (raw.trim().lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> true
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
    }
}
