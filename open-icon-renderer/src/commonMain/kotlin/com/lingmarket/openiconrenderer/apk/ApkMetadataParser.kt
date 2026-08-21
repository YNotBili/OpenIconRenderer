package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.api.ApkMetadata
import com.lingmarket.openiconrenderer.api.ApkPermission
import com.lingmarket.openiconrenderer.arsc.ResourceTable
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.XmlAttribute
import com.lingmarket.openiconrenderer.axml.XmlNode
import com.lingmarket.openiconrenderer.zip.ZipArchive

/**
 * Reads package / version / SDK / label / uses-permission from AndroidManifest.xml
 * and native ABIs from `lib/` entry paths.
 */
internal object ApkMetadataParser {
    data class Parsed(
        val metadata: ApkMetadata,
        val resources: ResourceTable?,
    )

    fun parse(zip: ZipArchive): ApkMetadata? = parseWithResources(zip)?.metadata

    fun parseWithResources(zip: ZipArchive): Parsed? {
        val manifest = zip.readEntry("AndroidManifest.xml") ?: return null
        val root = runCatching { BinaryXmlParser(manifest).parse() }.getOrNull() ?: return null
        val resources = zip.mapEntry("resources.arsc")?.let { mapped ->
            runCatching {
                ResourceTable(mapped.data, mapped.offset, mapped.length)
            }.getOrNull()
        }
        val metadata = parseManifest(root, architecturesOf(zip), resources) ?: return null
        return Parsed(metadata, resources)
    }

    internal fun parseManifest(
        root: XmlNode,
        architectures: List<String>,
        resources: ResourceTable? = null,
    ): ApkMetadata? {
        val packageName = attr(root, "package")?.trim().orEmpty()
        if (packageName.isBlank()) return null

        val versionCode = attrInt(root, "versionCode") ?: 1
        val versionName = attr(root, "versionName")?.trim()?.takeIf { it.isNotEmpty() }

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
                        applicationLabel = resolveLabel(attr(node, "label"), resources)
                    }
                    if (applicationIcon == null) {
                        applicationIcon = attr(node, "icon")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                "activity-alias" -> {
                    val enabled = attr(node, "enabled") ?: "true"
                    if (enabled != "false" && aliasIcon == null) {
                        aliasIcon = attr(node, "icon")?.trim()?.takeIf { it.isNotEmpty() }
                    }
                }
                "uses-permission", "uses-permission-sdk-23" -> {
                    val name = attr(node, "name")?.trim().orEmpty()
                    if (name.isNotEmpty() && name !in permissionByName) {
                        val required = attr(node, "required")?.let { parseBool(it) } ?: true
                        permissionByName[name] = ApkPermission(
                            name = name,
                            maxSdkVersion = attrInt(node, "maxSdkVersion"),
                            required = required,
                        )
                    }
                }
            }
            node.children.forEach(::walk)
        }
        walk(root)

        return ApkMetadata(
            packageName = packageName,
            versionCode = versionCode.coerceAtLeast(1),
            versionName = versionName,
            applicationLabel = applicationLabel,
            minSdk = minSdk,
            targetSdk = targetSdk ?: minSdk,
            architectures = architectures,
            permissions = permissionByName.values.sortedBy { it.name },
            launcherIconRef = applicationIcon ?: aliasIcon,
        )
    }

    private fun resolveLabel(raw: String?, resources: ResourceTable?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!value.startsWith("@")) return value
        val resId = parseResourceId(value) ?: return null
        return resources?.resolveString(resId)
    }

    /** Accepts `@7f0c0001`, `@0x7f0c0001`, or bare hex. */
    internal fun parseResourceId(raw: String): Int? {
        val s = raw.trim().removePrefix("@").removePrefix("0x").removePrefix("0X")
        if (s.isEmpty() || s.any { it !in "0123456789abcdefABCDEF" }) return null
        return s.toLongOrNull(16)?.toInt()
    }

    private fun architecturesOf(zip: ZipArchive): List<String> {
        val abis = linkedSetOf<String>()
        for (name in zip.entryNames) {
            if (!name.startsWith("lib/")) continue
            val abi = name.removePrefix("lib/").substringBefore('/', missingDelimiterValue = "")
            if (abi.isNotEmpty()) abis.add(abi)
        }
        return abis.toList()
    }

    private fun attr(node: XmlNode, name: String): String? {
        for (attribute in node.attributes) {
            if (!nameMatches(attribute, name)) continue
            val tv = attribute.typedValue
            if (tv != null) {
                val fromType = when (tv.type) {
                    BinaryXmlParser.TYPE_STRING ->
                        attribute.rawValue?.takeIf { it.isNotBlank() } ?: tv.data.toString()
                    BinaryXmlParser.TYPE_REFERENCE ->
                        attribute.rawValue?.takeIf { it.isNotBlank() }
                            ?: "@${tv.data.toUInt().toString(16).padStart(8, '0')}"
                    BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX -> tv.data.toString()
                    TYPE_INT_BOOLEAN -> if (tv.data != 0) "true" else "false"
                    else -> attribute.rawValue?.takeIf { it.isNotBlank() } ?: tv.data.toString()
                }
                if (fromType.isNotBlank()) return fromType
            }
            attribute.rawValue?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun attrInt(node: XmlNode, name: String): Int? =
        attr(node, name)?.toIntOrNull()

    private fun parseBool(raw: String): Boolean =
        when (raw.trim().lowercase()) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> true
        }

    private fun nameMatches(attribute: XmlAttribute, name: String): Boolean {
        val attrName = attribute.name
        return attrName == name ||
            attrName.endsWith(":$name") ||
            (attrName.endsWith(name) &&
                (attrName.length == name.length ||
                    attrName[attrName.length - name.length - 1] == ':'))
    }

    private const val TYPE_INT_BOOLEAN = 0x12
}
