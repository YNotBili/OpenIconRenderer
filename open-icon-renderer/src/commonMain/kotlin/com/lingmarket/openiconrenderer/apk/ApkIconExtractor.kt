package com.lingmarket.openiconrenderer.apk

import com.lingmarket.openiconrenderer.api.ApkMetadata
import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.IconMask
import com.lingmarket.openiconrenderer.arsc.ResolvedResource
import com.lingmarket.openiconrenderer.arsc.ResourceTable
import com.lingmarket.openiconrenderer.axml.BinaryXmlParser
import com.lingmarket.openiconrenderer.axml.XmlNode
import com.lingmarket.openiconrenderer.canvas.RgbaBitmap
import com.lingmarket.openiconrenderer.canvas.RgbaCanvas
import com.lingmarket.openiconrenderer.canvas.colorFromString
import com.lingmarket.openiconrenderer.image.ImageDecoder
import com.lingmarket.openiconrenderer.png.PngEncoder
import com.lingmarket.openiconrenderer.vector.FillPaint
import com.lingmarket.openiconrenderer.vector.GradientStop
import com.lingmarket.openiconrenderer.vector.PathDataParser
import com.lingmarket.openiconrenderer.vector.Affine2
import com.lingmarket.openiconrenderer.vector.VectorPath
import com.lingmarket.openiconrenderer.vector.VectorRasterizer
import com.lingmarket.openiconrenderer.vector.androidColorToArgb
import com.lingmarket.openiconrenderer.vector.parseAndroidFloat
import com.lingmarket.openiconrenderer.util.BinaryData
import com.lingmarket.openiconrenderer.util.HeapBinaryData
import com.lingmarket.openiconrenderer.util.StageClock
import com.lingmarket.openiconrenderer.zip.ZipArchive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

internal class ApkIconExtractor(
    apkData: BinaryData,
    options: IconExtractOptions,
    stages: StageClock? = null,
) {
    constructor(apkBytes: ByteArray, options: IconExtractOptions) : this(HeapBinaryData(apkBytes), options)

    /** Mutable so [IconSession] can reuse ZIP/ARSC across output sizes. */
    var options: IconExtractOptions = options
        private set

    var stages: StageClock? = stages

    fun updateOptions(next: IconExtractOptions) {
        options = next
    }

    private val outputSize: Int get() = options.outputSize
    private val verbose: Boolean get() = options.verbose
    private val zip: ZipArchive
    private val resources: ResourceTable? by lazy { loadResourceTable() }

    init {
        stages?.start("zip")
        zip = ZipArchive(apkData)
        stages?.stop()
    }

    private fun loadResourceTable(): ResourceTable? {
        val clock = stages
        if (clock == null) {
            val mapped = zip.mapEntry("resources.arsc") ?: return null
            return runCatching {
                ResourceTable(mapped.data, mapped.offset, mapped.length)
            }.getOrNull()
        }
        // May be called mid-resolve; attribute cost to arsc then resume resolve.
        clock.start("arsc")
        return try {
            val mapped = zip.mapEntry("resources.arsc") ?: return null
            runCatching {
                ResourceTable(mapped.data, mapped.offset, mapped.length)
            }.getOrNull()
        } finally {
            clock.stop()
            clock.start("resolve")
        }
    }

    fun extract(): ExtractResult? {
        val rec = record() ?: return null
        return renderRecording(rec)
    }

    /** Resolve launcher icon into a size-independent [IconRecording] (no raster at outputSize). */
    fun record(): IconRecording? {
        stages?.start("resolve")
        return try {
            val iconRef = findManifestIcon() ?: findManifestIconFromAlias()
            when {
                iconRef != null -> {
                    trace("manifest icon: $iconRef")
                    recordDrawableRef(iconRef, depth = 0, iconRef = iconRef)
                }
                else -> null
            } ?: findAdaptiveIconXmlInZip()?.let { path ->
                trace("adaptive icon fallback: $path")
                recordXmlDrawable(path, depth = 0, iconRef = null)
            } ?: tryRasterFallback()?.let { raw ->
                IconRecording.Raster(iconRef = null, sourcePath = raw.sourcePath, bitmap = raw.bitmap)
            }
        } finally {
            stages?.stop()
        }
    }

    /** Rasterize / mask a previously recorded icon at the current [options.outputSize]. */
    fun renderRecording(recording: IconRecording): ExtractResult? {
        val raw = when (recording) {
            is IconRecording.Adaptive -> renderAdaptiveRecording(recording)
            is IconRecording.Raster -> ExtractResult(recording.bitmap, recording.sourcePath)
        }
        val sized = materializeOutput(raw)
        if (options.mask != IconMask.CIRCLE) return sized
        return stages.measureOr("mask") {
            ExtractResult(applyCircleMask(sized.bitmap), sized.sourcePath)
        }
    }

    private inline fun <T> StageClock?.measureOr(name: String, block: () -> T): T {
        if (this == null) return block()
        return measure(name, block)
    }

    fun inspect(): IconInspection {
        val iconRef = findManifestIcon() ?: findManifestIconFromAlias()
        val resolvedPath = when {
            iconRef != null -> resolveDrawablePath(iconRef, depth = 0)
            else -> null
        } ?: findRasterFallbackPath() ?: findAdaptiveIconXmlInZip()
        return IconInspection(iconRef, resolvedPath)
    }

    fun parseMetadata(): ApkMetadata? = ApkMetadataParser(zip, resources).parse()

    private fun findAdaptiveIconXmlInZip(): String? {
        // Last-resort only: scanning every res/*.xml is very expensive on large APKs.
        val candidates = zip.entryNames.asSequence()
            .filter { it.startsWith("res/") && it.endsWith(".xml") }
            .sortedByDescending { scoreAdaptiveCandidate(it) }
        for (path in candidates) {
            val data = zip.readEntry(path) ?: continue
            val root = runCatching { BinaryXmlParser(data).parse() }.getOrNull() ?: continue
            if (root.tag == "adaptive-icon") return path
        }
        return null
    }

    private fun scoreAdaptiveCandidate(path: String): Int {
        val lower = path.lowercase()
        var score = 0
        if ("mipmap" in lower) score += 4
        if ("launcher" in lower || "ic_launcher" in lower) score += 3
        if (lower.endsWith("/e4.xml") || lower.endsWith("e4.xml")) score += 2
        return score
    }

    private fun findRasterFallbackPath(): String? {
        val candidates = zip.entryNames.filter { path ->
            val lower = path.lowercase()
            if (lower.endsWith(".xml")) return@filter false
            if (!lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) return@filter false
            if (!lower.contains("/mipmap") && !lower.contains("/drawable") && !Regex("^r/[^/]+/ic_launcher").containsMatchIn(lower)) return@filter false
            val base = lower.substringAfterLast('/')
            base.startsWith("ic_launcher") || base.startsWith("launcher_icon") || "ic_launcher" in base
        }
        return sortByDpi(candidates).firstOrNull()
    }

    private fun resolveDrawablePath(ref: String, depth: Int): String? {
        if (depth > 5) return null
        if (ref.startsWith("#")) return null
        val raw = ref.removePrefix("@")
        if (raw.startsWith("0x", ignoreCase = true) || Regex("^[0-9a-fA-F]{8}$").matches(raw)) {
            val resId = raw.removePrefix("0x").removePrefix("0X").toInt(16)
            return resolveResourceIdPath(resId, depth)
        }
        if ("/" !in raw) return null
        val parts = raw.split("/", limit = 2)
        var resType = parts[0]
        if (":" in resType) resType = resType.substringAfter(":")
        val resName = parts[1]
        if (resType == "color") return null
        return findResourcePath(resType, resName, depth)
    }

    private fun resolveResourceIdPath(resId: Int, depth: Int): String? {
        val configs = resources?.resolveReference(resId) ?: return null
        val paths = configs.filterIsInstance<ResolvedResource.FilePath>().map { it.path }
        val ordered = if (options.preferAdaptive) {
            sortByDpi(paths.filter { it.endsWith(".xml") }) + sortByDpi(paths.filter { !it.endsWith(".xml") })
        } else {
            sortByDpi(paths.filter { !it.endsWith(".xml") }) + sortByDpi(paths.filter { it.endsWith(".xml") })
        }
        for (path in ordered) {
            if (!zip.contains(path)) continue
            if (path.endsWith(".xml")) {
                resolveXmlDrawablePath(path, depth + 1)?.let { return it }
                    ?: return path // adaptive/vector xml itself
            } else {
                return path
            }
        }
        return null
    }

    private fun findResourcePath(resType: String, resName: String, depth: Int): String? {
        val types = buildList {
            add(resType)
            if (resType == "drawable") add("mipmap")
            if (resType == "mipmap") add("drawable")
        }
        val pattern = Regex("^res/(?:${types.joinToString("|")})[^/]*/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$")
        var matches = zip.entryNames.filter { pattern.matches(it) }
        if (matches.isEmpty()) {
            val short = Regex("^r/[^/]+/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$", RegexOption.IGNORE_CASE)
            matches = zip.entryNames.filter { short.matches(it) }
        }
        val raster = matches.filter { !it.endsWith(".xml") }
        val xml = matches.filter { it.endsWith(".xml") }
        raster.firstOrNull()?.let { return sortByDpi(raster).first() }
        if (depth < 5) {
            for (path in sortByDpi(xml)) {
                resolveXmlDrawablePath(path, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun resolveXmlDrawablePath(path: String, depth: Int): String? {
        val data = zip.readEntry(path) ?: return null
        val root = BinaryXmlParser(data).parse() ?: return null
        return inspectNodePath(root, depth, path)
    }

    private fun inspectNodePath(node: XmlNode, depth: Int, sourcePath: String): String? {
        return when (node.tag) {
            "adaptive-icon" -> {
                for (child in node.children) {
                    when (child.tag) {
                        "background", "foreground" -> inspectDrawableElementPath(child, depth + 1)?.let { return it }
                    }
                }
                null
            }
            "bitmap" -> attr(node, "src")?.let { resolveDrawablePath(it, depth) }
            "inset" -> attr(node, "drawable")?.let { resolveDrawablePath(it, depth + 1) }
            else -> node.children.firstNotNullOfOrNull { inspectNodePath(it, depth, sourcePath) }
        }
    }

    private fun inspectDrawableElementPath(node: XmlNode, depth: Int): String? {
        attr(node, "drawable")?.let { return resolveDrawablePath(it, depth) }
        for (child in node.children) {
            inspectNodePath(child, depth, "")?.let { return it }
        }
        return null
    }

    internal data class IconInspection(val iconRef: String?, val resolvedPath: String?)

    private fun findManifestIcon(): String? {
        val manifest = zip.readEntry("AndroidManifest.xml") ?: return null
        val root = BinaryXmlParser(manifest).parse() ?: return null
        return findIconInNode(root)
    }

    private fun findManifestIconFromAlias(): String? {
        val manifest = zip.readEntry("AndroidManifest.xml") ?: return null
        val root = BinaryXmlParser(manifest).parse() ?: return null
        val aliases = mutableListOf<XmlNode>()
        collectByTag(root, "activity-alias", aliases)
        for (alias in aliases) {
            val enabled = attr(alias, "enabled") ?: "true"
            if (enabled == "false") continue
            val icon = attr(alias, "icon")
            if (!icon.isNullOrBlank()) return icon
        }
        return null
    }

    private fun findIconInNode(node: XmlNode): String? {
        if (node.tag == "application") {
            attr(node, "icon")?.let { return it }
        }
        for (child in node.children) {
            findIconInNode(child)?.let { return it }
        }
        return null
    }

    private fun collectByTag(node: XmlNode, tag: String, out: MutableList<XmlNode>) {
        if (node.tag == tag) out.add(node)
        node.children.forEach { collectByTag(it, tag, out) }
    }

    private fun tryRasterFallback(): ExtractResult? {
        val candidates = zip.entryNames.filter { path ->
            val lower = path.lowercase()
            if (lower.endsWith(".xml")) return@filter false
            if (!lower.endsWith(".png") && !lower.endsWith(".webp") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) return@filter false
            if (!lower.contains("/mipmap") && !lower.contains("/drawable") && !Regex("^r/[^/]+/ic_launcher").containsMatchIn(lower)) return@filter false
            val base = lower.substringAfterLast('/')
            base.startsWith("ic_launcher") || base.startsWith("launcher_icon") || "ic_launcher" in base
        }
        for (path in sortByDpi(candidates)) {
            renderRaster(path)?.let {
                trace("fallback raster: $path")
                return it
            }
        }
        return null
    }

    private fun resolveDrawableRef(ref: String, depth: Int): ExtractResult? {
        if (depth > 5) return null
        if (ref.startsWith("#")) {
            val color = colorFromString(ref) ?: return null
            return resultFromBitmap(uniformColorBitmap(color), null)
        }
        val raw = ref.removePrefix("@")
        if (raw.startsWith("0x", ignoreCase = true) || Regex("^[0-9a-fA-F]{8}$").matches(raw)) {
            val resId = raw.removePrefix("0x").removePrefix("0X").toInt(16)
            return resolveResourceId(resId, depth)
        }
        if ("/" !in raw) return null
        val parts = raw.split("/", limit = 2)
        var resType = parts[0]
        if (":" in resType) resType = resType.substringAfter(":")
        val resName = parts[1]
        if (resType == "color") {
            val color = resources?.findColorByName(resName)
            val argb = color ?: return null
            return resultFromBitmap(uniformColorBitmap(argb), null)
        }
        return findResourceImage(resType, resName, depth)
    }

    private fun resolveResourceId(resId: Int, depth: Int): ExtractResult? {
        val configs = resources?.resolveReference(resId) ?: return null
        val paths = configs.filterIsInstance<ResolvedResource.FilePath>().map { it.path }
        val colors = configs.filterIsInstance<ResolvedResource.Color>()
        val xmlPaths = paths.filter { it.endsWith(".xml") }
        val rasterPaths = paths.filter { !it.endsWith(".xml") }

        val ordered = if (options.preferAdaptive) {
            sortByDpi(xmlPaths) + sortByDpi(rasterPaths)
        } else {
            sortByDpi(rasterPaths) + sortByDpi(xmlPaths)
        }
        for (path in ordered) {
            if (!zip.contains(path)) continue
            if (path.endsWith(".xml")) {
                resolveXmlDrawable(path, depth + 1)?.let { return it }
            } else {
                renderRaster(path)?.let { return it }
            }
        }
        for (color in colors) {
            return resultFromBitmap(uniformColorBitmap(color.argb), null)
        }
        return null
    }

    private fun findResourceImage(resType: String, resName: String, depth: Int): ExtractResult? {
        val types = buildList {
            add(resType)
            if (resType == "drawable") add("mipmap")
            if (resType == "mipmap") add("drawable")
        }
        val pattern = Regex("^res/(?:${types.joinToString("|")})[^/]*/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$")
        var matches = zip.entryNames.filter { pattern.matches(it) }
        if (matches.isEmpty()) {
            val short = Regex("^r/[^/]+/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$", RegexOption.IGNORE_CASE)
            matches = zip.entryNames.filter { short.matches(it) }
        }
        val raster = matches.filter { !it.endsWith(".xml") }
        val xml = matches.filter { it.endsWith(".xml") }
        for (path in sortByDpi(raster)) {
            renderRaster(path)?.let { return it }
        }
        if (depth < 5) {
            for (path in sortByDpi(xml)) {
                resolveXmlDrawable(path, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun resolveXmlDrawable(path: String, depth: Int): ExtractResult? {
        val data = zip.readEntry(path) ?: return null
        val root = BinaryXmlParser(data).parse() ?: return null
        return renderNode(root, depth, path)
    }

    private fun renderNode(node: XmlNode, depth: Int, sourcePath: String): ExtractResult? {
        return when (node.tag) {
            "adaptive-icon" -> renderAdaptiveIcon(node, depth)
            "vector" -> renderVector(node, sourcePath)
            "bitmap" -> attr(node, "src")?.let { resolveDrawableRef(it, depth) }
            "layer-list" -> renderLayerList(node, depth)
            "shape" -> renderShape(node)
            "inset" -> attr(node, "drawable")?.let { resolveDrawableRef(it, depth + 1) }
                ?: node.children.firstNotNullOfOrNull { renderNode(it, depth + 1, sourcePath) }
            else -> node.children.firstNotNullOfOrNull { renderNode(it, depth, sourcePath) }
        }
    }

    private fun renderAdaptiveIcon(node: XmlNode, depth: Int): ExtractResult? {
        val rec = recordAdaptiveIcon(node, depth, iconRef = null) ?: return null
        return renderAdaptiveRecording(rec)
    }

    private fun recordAdaptiveIcon(node: XmlNode, depth: Int, iconRef: String?): IconRecording.Adaptive? {
        var bg: IconRecording.Background = IconRecording.Background.None
        var fg: IconRecording.Foreground = IconRecording.Foreground.None
        for (child in node.children) {
            when (child.tag) {
                "background" -> {
                    val resolved = resolveDrawableElement(child, depth + 1)
                    bg = when {
                        resolved == null -> IconRecording.Background.None
                        else -> {
                            val solid = resolved.bitmap.uniformArgb
                            if (solid != null) IconRecording.Background.Solid(solid)
                            else IconRecording.Background.Bitmap(resolved.bitmap)
                        }
                    }
                }
                "foreground" -> {
                    val vec = tryResolveVectorElement(child, depth + 1)
                    fg = if (vec != null) {
                        IconRecording.Foreground.Vector(vec.paths, vec.vpW, vec.vpH, vec.layerAlpha)
                    } else {
                        val bmp = resolveDrawableElement(child, depth + 1)?.bitmap
                        if (bmp != null) IconRecording.Foreground.Bitmap(bmp)
                        else IconRecording.Foreground.None
                    }
                }
            }
        }
        if (bg is IconRecording.Background.None && fg is IconRecording.Foreground.None) return null
        return IconRecording.Adaptive(
            iconRef = iconRef,
            sourcePath = "adaptive-icon",
            background = bg,
            foreground = fg,
        )
    }

    private fun renderAdaptiveRecording(recording: IconRecording.Adaptive): ExtractResult {
        val canvas = when (val bg = recording.background) {
            is IconRecording.Background.Solid -> {
                if ((bg.argb ushr 24) == 0xFF) {
                    RgbaBitmap.filled(outputSize, outputSize, bg.argb)
                } else {
                    val c = RgbaBitmap.filled(outputSize, outputSize, 0)
                    RgbaCanvas.composite(c, RgbaBitmap.filled(outputSize, outputSize, bg.argb))
                    c
                }
            }
            is IconRecording.Background.Bitmap -> {
                val c = RgbaBitmap.filled(outputSize, outputSize, 0)
                val solid = bg.bitmap.uniformArgb
                if (solid != null && (solid ushr 24) == 0xFF) {
                    c.pixels.fill(solid)
                } else {
                    RgbaCanvas.composite(c, RgbaCanvas.resize(bg.bitmap, outputSize, outputSize))
                }
                c
            }
            IconRecording.Background.None -> RgbaBitmap.filled(outputSize, outputSize, 0)
        }

        when (val fg = recording.foreground) {
            is IconRecording.Foreground.Vector -> {
                // Group opacity (<group android:alpha>) needs an offscreen layer: draw children,
                // then apply layer alpha, then SrcOver onto BG. Baking α into each path is wrong
                // when opaque children overlap. Plain vectors (layerAlpha≈1) stay direct-on-BG.
                val needsIsolation = options.isolateForeground || fg.layerAlpha < 0.999f
                if (needsIsolation) {
                    val fgLayer = RgbaBitmap.filled(outputSize, outputSize, 0)
                    stages.measureOr("tess") {
                        VectorRasterizer.rasterizeOnto(
                            fgLayer, fg.paths, fg.viewportWidth, fg.viewportHeight,
                        )
                    }
                    if (fg.layerAlpha < 0.999f) {
                        RgbaCanvas.scaleStraightAlpha(fgLayer, fg.layerAlpha)
                    }
                    stages.measureOr("composite") {
                        RgbaCanvas.composite(canvas, fgLayer)
                    }
                } else {
                    stages.measureOr("tess") {
                        VectorRasterizer.rasterizeOnto(
                            canvas, fg.paths, fg.viewportWidth, fg.viewportHeight,
                        )
                    }
                }
            }
            is IconRecording.Foreground.Bitmap -> {
                val overlay = RgbaCanvas.resize(fg.bitmap, outputSize, outputSize)
                val solidFg = overlay.uniformArgb
                if (solidFg != null && (solidFg ushr 24) == 0xFF) {
                    canvas.pixels.fill(solidFg)
                } else {
                    RgbaCanvas.composite(canvas, overlay)
                }
            }
            IconRecording.Foreground.None -> Unit
        }
        return resultFromBitmap(canvas, recording.sourcePath)
    }

    private fun recordDrawableRef(ref: String, depth: Int, iconRef: String?): IconRecording? {
        if (depth > 5) return null
        if (ref.startsWith("#")) {
            val color = colorFromString(ref) ?: return null
            return IconRecording.Raster(
                iconRef = iconRef,
                sourcePath = ref,
                bitmap = RgbaBitmap(1, 1, intArrayOf(color), uniformArgb = color),
            )
        }
        val raw = ref.removePrefix("@")
        if (raw.startsWith("0x", ignoreCase = true) || Regex("^[0-9a-fA-F]{8}$").matches(raw)) {
            val resId = raw.removePrefix("0x").removePrefix("0X").toInt(16)
            return recordResourceId(resId, depth, iconRef)
        }
        if ("/" !in raw) return null
        val parts = raw.split("/", limit = 2)
        var resType = parts[0]
        if (":" in resType) resType = resType.substringAfter(":")
        val resName = parts[1]
        if (resType == "color") {
            val argb = resources?.findColorByName(resName) ?: return null
            return IconRecording.Raster(
                iconRef = iconRef,
                sourcePath = ref,
                bitmap = RgbaBitmap(1, 1, intArrayOf(argb), uniformArgb = argb),
            )
        }
        return recordResourceImage(resType, resName, depth, iconRef)
    }

    private fun recordResourceId(resId: Int, depth: Int, iconRef: String?): IconRecording? {
        val configs = resources?.resolveReference(resId) ?: return null
        val paths = configs.filterIsInstance<ResolvedResource.FilePath>().map { it.path }
        val colors = configs.filterIsInstance<ResolvedResource.Color>()
        val xmlPaths = paths.filter { it.endsWith(".xml") }
        val rasterPaths = paths.filter { !it.endsWith(".xml") }
        val ordered = if (options.preferAdaptive) {
            sortByDpi(xmlPaths) + sortByDpi(rasterPaths)
        } else {
            sortByDpi(rasterPaths) + sortByDpi(xmlPaths)
        }
        for (path in ordered) {
            if (!zip.contains(path)) continue
            if (path.endsWith(".xml")) {
                recordXmlDrawable(path, depth + 1, iconRef)?.let { return it }
            } else {
                renderRaster(path)?.let {
                    return IconRecording.Raster(iconRef, it.sourcePath, it.bitmap)
                }
            }
        }
        for (color in colors) {
            return IconRecording.Raster(
                iconRef = iconRef,
                sourcePath = null,
                bitmap = RgbaBitmap(1, 1, intArrayOf(color.argb), uniformArgb = color.argb),
            )
        }
        return null
    }

    private fun recordResourceImage(
        resType: String,
        resName: String,
        depth: Int,
        iconRef: String?,
    ): IconRecording? {
        val types = buildList {
            add(resType)
            if (resType == "drawable") add("mipmap")
            if (resType == "mipmap") add("drawable")
        }
        val pattern = Regex("^res/(?:${types.joinToString("|")})[^/]*/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$")
        var matches = zip.entryNames.filter { pattern.matches(it) }
        if (matches.isEmpty()) {
            val short = Regex("^r/[^/]+/${Regex.escape(resName)}\\.(png|webp|jpg|jpeg|xml)$", RegexOption.IGNORE_CASE)
            matches = zip.entryNames.filter { short.matches(it) }
        }
        val raster = matches.filter { !it.endsWith(".xml") }
        val xml = matches.filter { it.endsWith(".xml") }
        val ordered = if (options.preferAdaptive) {
            sortByDpi(xml) + sortByDpi(raster)
        } else {
            sortByDpi(raster) + sortByDpi(xml)
        }
        for (path in ordered) {
            if (path.endsWith(".xml")) {
                recordXmlDrawable(path, depth + 1, iconRef)?.let { return it }
            } else {
                renderRaster(path)?.let {
                    return IconRecording.Raster(iconRef, it.sourcePath, it.bitmap)
                }
            }
        }
        return null
    }

    private fun recordFromResolved(resolved: ExtractResult, iconRef: String?): IconRecording {
        return IconRecording.Raster(
            iconRef = iconRef,
            sourcePath = resolved.sourcePath,
            bitmap = resolved.bitmap,
        )
    }

    private fun recordXmlDrawable(path: String, depth: Int, iconRef: String?): IconRecording? {
        val data = zip.readEntry(path) ?: return null
        val root = BinaryXmlParser(data).parse() ?: return null
        return recordNode(root, depth, path, iconRef)
    }

    private fun recordNode(node: XmlNode, depth: Int, sourcePath: String, iconRef: String?): IconRecording? {
        return when (node.tag) {
            "adaptive-icon" -> recordAdaptiveIcon(node, depth, iconRef)
            else -> {
                val rendered = renderNode(node, depth, sourcePath) ?: return null
                IconRecording.Raster(iconRef = iconRef, sourcePath = rendered.sourcePath, bitmap = rendered.bitmap)
            }
        }
    }

    private data class VectorDrawable(
        val paths: List<VectorPath>,
        val vpW: Float,
        val vpH: Float,
        val layerAlpha: Float = 1f,
    )

    /** Resolve a drawable element to vector geometry without rasterizing. */
    private fun tryResolveVectorElement(node: XmlNode, depth: Int): VectorDrawable? {
        if (depth > 5) return null
        attr(node, "drawable")?.let { return tryResolveVectorRef(it, depth) }
        for (child in node.children) {
            when (child.tag) {
                "vector" -> return vectorFromNode(child)
                else -> tryResolveVectorElement(child, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun tryResolveVectorRef(ref: String, depth: Int): VectorDrawable? {
        if (depth > 5) return null
        if (ref.startsWith("#")) return null
        val raw = ref.removePrefix("@")
        if (raw.startsWith("0x", ignoreCase = true) || Regex("^[0-9a-fA-F]{8}$").matches(raw)) {
            val resId = raw.removePrefix("0x").removePrefix("0X").toInt(16)
            val configs = resources?.resolveReference(resId) ?: return null
            val paths = configs.filterIsInstance<ResolvedResource.FilePath>().map { it.path }
            for (path in sortByDpi(paths.filter { it.endsWith(".xml") })) {
                if (!zip.contains(path)) continue
                loadVectorFromPath(path)?.let { return it }
            }
            return null
        }
        if ("/" !in raw) return null
        val parts = raw.split("/", limit = 2)
        var resType = parts[0]
        if (":" in resType) resType = resType.substringAfter(":")
        val resName = parts[1]
        if (resType == "color") return null
        val types = buildList {
            add(resType)
            if (resType == "drawable") add("mipmap")
            if (resType == "mipmap") add("drawable")
        }
        val pattern = Regex("^res/(?:${types.joinToString("|")})[^/]*/${Regex.escape(resName)}\\.xml$")
        var matches = zip.entryNames.filter { pattern.matches(it) }
        if (matches.isEmpty()) {
            val short = Regex("^r/[^/]+/${Regex.escape(resName)}\\.xml$", RegexOption.IGNORE_CASE)
            matches = zip.entryNames.filter { short.matches(it) }
        }
        for (path in sortByDpi(matches)) {
            loadVectorFromPath(path)?.let { return it }
        }
        return null
    }

    private fun loadVectorFromPath(path: String): VectorDrawable? {
        val data = zip.readEntry(path) ?: return null
        val root = BinaryXmlParser(data).parse() ?: return null
        return when (root.tag) {
            "vector" -> vectorFromNode(root)
            "adaptive-icon" -> null
            else -> root.children.firstNotNullOfOrNull { child ->
                if (child.tag == "vector") vectorFromNode(child) else null
            }
        }
    }

    private fun vectorFromNode(node: XmlNode): VectorDrawable? {
        val vpW = attr(node, "viewportWidth")?.let { parseAndroidFloat(it) } ?: return null
        val vpH = attr(node, "viewportHeight")?.let { parseAndroidFloat(it) } ?: return null
        if (vpW <= 0f || vpH <= 0f) return null
        // Single root <group android:alpha> → true layer opacity (Android offscreen + alpha).
        val only = node.children.singleOrNull()
        val layerAlpha = if (only != null && only.tag == "group") {
            (attr(only, "alpha")?.let { parseAndroidFloat(it) } ?: 1f).coerceIn(0f, 1f)
        } else {
            1f
        }
        // Walk from the <vector> root so a lone <group> still gets translate/scale/rotate.
        val paths = collectVectorPaths(node)
        if (paths.isEmpty() || paths.none { it.fill != null }) return null
        return VectorDrawable(paths, vpW, vpH, layerAlpha)
    }

    private fun resolveDrawableElement(node: XmlNode, depth: Int): ExtractResult? {
        attr(node, "drawable")?.let { return resolveDrawableRef(it, depth) }
        for (child in node.children) {
            when (child.tag) {
                "color" -> {
                    val color = attr(child, "color")?.let { resolveColorValue(it) }
                    if (color != null) {
                        return resultFromBitmap(uniformColorBitmap(color), null)
                    }
                }
                "gradient" -> parseGradientPaint(child)?.let { paint ->
                    val c = when (paint) {
                        is FillPaint.Solid -> paint.argb
                        is FillPaint.LinearGradient -> paint.stops.firstOrNull()?.argb
                        is FillPaint.RadialGradient -> paint.stops.firstOrNull()?.argb
                    } ?: return@let null
                    return resultFromBitmap(uniformColorBitmap(c), null)
                }
                else -> renderNode(child, depth, "")?.let { return it }
            }
        }
        return null
    }

    private fun renderVector(node: XmlNode, sourcePath: String): ExtractResult? {
        val vector = vectorFromNode(node) ?: return null
        val bitmap = stages.measureOr("tess") {
            VectorRasterizer.rasterize(vector.paths, vector.vpW, vector.vpH, outputSize)
        }
        stages?.start("resolve")
        return resultFromBitmap(bitmap, sourcePath)
    }

    private fun collectVectorPaths(
        node: XmlNode,
        matrix: Affine2 = Affine2.IDENTITY,
        out: MutableList<VectorPath> = mutableListOf(),
    ): List<VectorPath> {
        for (child in node.children) {
            when (child.tag) {
                "path" -> {
                    val pathData = attr(child, "pathData") ?: continue
                    val fill = matrix.mapFill(resolveFillPaint(attr(child, "fillColor")))
                    val stroke = attr(child, "strokeColor")?.let { resolveColorValue(it) }
                    val strokeWidth = (attr(child, "strokeWidth")?.let { parseAndroidFloat(it) } ?: 0f) *
                        matrix.meanScale()
                    val fillAlpha = attr(child, "fillAlpha")?.let { parseAndroidFloat(it) } ?: 1f
                    val strokeAlpha = attr(child, "strokeAlpha")?.let { parseAndroidFloat(it) } ?: 1f
                    val fillTypeRaw = attr(child, "fillType")?.trim()?.lowercase()
                    val fillType = when (fillTypeRaw) {
                        "1", "evenodd", "even_odd", "even-odd" ->
                            com.lingmarket.openiconrenderer.vector.FillType.EVEN_ODD
                        else -> com.lingmarket.openiconrenderer.vector.FillType.NON_ZERO
                    }
                    out.add(
                        VectorPath(
                            matrix.mapCommands(PathDataParser.parse(pathData)),
                            fill,
                            stroke,
                            strokeWidth,
                            fillAlpha,
                            strokeAlpha,
                            fillType,
                        ),
                    )
                }
                "group" -> collectVectorPaths(child, matrix.compose(groupAffine(child)), out)
            }
        }
        return out
    }

    private fun groupAffine(node: XmlNode): Affine2 {
        val translateX = attr(node, "translateX")?.let { parseAndroidFloat(it) } ?: 0f
        val translateY = attr(node, "translateY")?.let { parseAndroidFloat(it) } ?: 0f
        val scaleX = attr(node, "scaleX")?.let { parseAndroidFloat(it) } ?: 1f
        val scaleY = attr(node, "scaleY")?.let { parseAndroidFloat(it) } ?: 1f
        val rotation = attr(node, "rotation")?.let { parseAndroidFloat(it) } ?: 0f
        val pivotX = attr(node, "pivotX")?.let { parseAndroidFloat(it) } ?: 0f
        val pivotY = attr(node, "pivotY")?.let { parseAndroidFloat(it) } ?: 0f
        return Affine2.androidGroup(translateX, translateY, scaleX, scaleY, rotation, pivotX, pivotY)
    }

    private fun resolveFillPaint(ref: String?): FillPaint? {
        if (ref.isNullOrBlank()) return null
        resolveColorValue(ref)?.let { return FillPaint.Solid(it) }
        val resId = parseResId(ref) ?: return null
        val configs = resources?.resolveReference(resId) ?: return null
        for (value in configs) {
            when (value) {
                is ResolvedResource.Color -> return FillPaint.Solid(value.argb)
                is ResolvedResource.FilePath -> {
                    if (value.path.endsWith(".xml")) {
                        parseGradientFromPath(value.path)?.let { return it }
                    }
                }
                is ResolvedResource.Reference -> {
                    resolveFillPaint("@${value.resId.toUInt().toString(16).padStart(8, '0')}")?.let { return it }
                }
            }
        }
        return null
    }

    private fun resolveColorValue(ref: String): Int? {
        colorFromString(ref)?.let { return it }
        androidColorToArgb(ref)?.let { return it }
        val resId = parseResId(ref) ?: return null
        val configs = resources?.resolveReference(resId) ?: return null
        for (value in configs) {
            when (value) {
                is ResolvedResource.Color -> return value.argb
                is ResolvedResource.Reference ->
                    resolveColorValue("@${value.resId.toUInt().toString(16).padStart(8, '0')}")?.let { return it }
                is ResolvedResource.FilePath -> Unit
            }
        }
        return null
    }

    private fun parseResId(ref: String): Int? {
        val raw = ref.removePrefix("@").removePrefix("?")
        val hex = raw.removePrefix("0x").removePrefix("0X")
        return hex.toUIntOrNull(16)?.toInt()
    }

    private fun parseGradientFromPath(path: String): FillPaint? {
        val data = zip.readEntry(path) ?: return null
        val root = BinaryXmlParser(data).parse() ?: return null
        val gradient = if (root.tag == "gradient") root else root.children.firstOrNull { it.tag == "gradient" }
        return gradient?.let { parseGradientPaint(it) }
    }

    private fun parseGradientPaint(node: XmlNode): FillPaint? {
        val stops = node.children.filter { it.tag == "item" }.mapNotNull { item ->
            val color = attr(item, "color")?.let { resolveColorValue(it) } ?: return@mapNotNull null
            val offset = attr(item, "offset")?.let { parseAndroidFloat(it) } ?: 0f
            GradientStop(offset, color)
        }.sortedBy { it.offset }
        if (stops.isEmpty()) {
            val start = attr(node, "startColor")?.let { resolveColorValue(it) }
            val end = attr(node, "endColor")?.let { resolveColorValue(it) }
            if (start != null && end != null) {
                return FillPaint.LinearGradient(0f, 0f, 108f, 108f, listOf(GradientStop(0f, start), GradientStop(1f, end)))
            }
            start?.let { return FillPaint.Solid(it) }
            end?.let { return FillPaint.Solid(it) }
            return null
        }
        val type = attr(node, "type")?.toIntOrNull() ?: 0
        return when (type) {
            1 -> { // radial
                val cx = attr(node, "centerX")?.let { parseAndroidFloat(it) } ?: 54f
                val cy = attr(node, "centerY")?.let { parseAndroidFloat(it) } ?: 54f
                val r = attr(node, "gradientRadius")?.let { parseAndroidFloat(it) } ?: 54f
                FillPaint.RadialGradient(cx, cy, r, stops)
            }
            else -> {
                val x0 = attr(node, "startX")?.let { parseAndroidFloat(it) } ?: 0f
                val y0 = attr(node, "startY")?.let { parseAndroidFloat(it) } ?: 0f
                val x1 = attr(node, "endX")?.let { parseAndroidFloat(it) } ?: 108f
                val y1 = attr(node, "endY")?.let { parseAndroidFloat(it) } ?: 108f
                FillPaint.LinearGradient(x0, y0, x1, y1, stops)
            }
        }
    }

    private fun renderLayerList(node: XmlNode, depth: Int): ExtractResult? {
        val canvas = RgbaBitmap.create(outputSize, outputSize, 0)
        var hasLayer = false
        for (child in node.children) {
            if (child.tag != "item") continue
            val layer = resolveDrawableElement(child, depth + 1)?.bitmap ?: continue
            RgbaCanvas.composite(canvas, RgbaCanvas.resize(layer, outputSize, outputSize))
            hasLayer = true
        }
        return if (hasLayer) resultFromBitmap(canvas, null) else null
    }

    private fun renderShape(node: XmlNode): ExtractResult? {
        for (child in node.children) {
            when (child.tag) {
                "solid" -> attr(child, "color")?.let { resolveColorValue(it) }?.let {
                    return resultFromBitmap(uniformColorBitmap(it), null)
                }
                "gradient" -> parseGradientPaint(child)?.let { paint ->
                    val c = when (paint) {
                        is FillPaint.Solid -> paint.argb
                        is FillPaint.LinearGradient -> paint.stops.firstOrNull()?.argb
                        is FillPaint.RadialGradient -> paint.stops.firstOrNull()?.argb
                    } ?: return@let null
                    return resultFromBitmap(uniformColorBitmap(c), null)
                }
            }
        }
        return null
    }

    private fun renderRaster(path: String): ExtractResult? {
        val bytes = zip.readEntry(path) ?: return null
        val bitmap = ImageDecoder.decode(bytes, targetSize = outputSize) ?: return null
        return resultFromBitmap(bitmap, path)
    }

    private fun resultFromBitmap(bitmap: RgbaBitmap, sourcePath: String?): ExtractResult {
        // Keep uniform 1×1 as-is; materializeOutput / adaptive expand once.
        if (bitmap.uniformArgb != null) return ExtractResult(bitmap, sourcePath)
        val out = if (bitmap.width == outputSize && bitmap.height == outputSize) bitmap
        else RgbaCanvas.resize(bitmap, outputSize, outputSize)
        return ExtractResult(out, sourcePath)
    }

    private fun materializeOutput(raw: ExtractResult): ExtractResult {
        val b = raw.bitmap
        if (b.width == outputSize && b.height == outputSize) return raw
        return ExtractResult(RgbaCanvas.resize(b, outputSize, outputSize), raw.sourcePath)
    }

    private fun applyCircleMask(src: RgbaBitmap): RgbaBitmap {
        val w = src.width
        val h = src.height
        val pixels = src.pixels
        if (w != h) {
            applyCircleMaskRowSpan(src, buildCircleRows(w.coerceAtMost(h), w, h))
            return src
        }
        applyCircleMaskRowSpan(src, circleMaskRows(w))
        return src
    }

    private fun applyCircleMaskRowSpan(src: RgbaBitmap, rows: CircleMaskRows) {
        val w = src.width
        val pixels = src.pixels
        val cx = rows.cx
        val rOuter = rows.rOuter
        val feather = rows.feather
        val spans = rows.spans
        for (y in 0 until src.height) {
            val row = y * w
            val base = y * 4
            val xLeftOuter = spans[base]
            if (xLeftOuter < 0) {
                pixels.fill(0, row, row + w)
                continue
            }
            val xRightOuter = spans[base + 1]
            val xLeftInner = spans[base + 2]
            val xRightInner = spans[base + 3]
            val dy2 = rows.dy2[y]
            if (xLeftOuter > 0) pixels.fill(0, row, row + xLeftOuter)
            if (xRightOuter + 1 < w) pixels.fill(0, row + xRightOuter + 1, row + w)
            if (xLeftInner > xRightInner) {
                // Entire chord is feather band.
                for (x in xLeftOuter..xRightOuter) {
                    softMaskPixel(pixels, row + x, x - cx, dy2, rOuter, feather)
                }
            } else {
                for (x in xLeftOuter until xLeftInner) {
                    softMaskPixel(pixels, row + x, x - cx, dy2, rOuter, feather)
                }
                for (x in (xRightInner + 1)..xRightOuter) {
                    softMaskPixel(pixels, row + x, x - cx, dy2, rOuter, feather)
                }
            }
        }
    }

    private fun softMaskPixel(pixels: IntArray, idx: Int, dx: Float, dy2: Float, rOuter: Float, feather: Float) {
        val dist = sqrt(dx * dx + dy2)
        val cover = ((rOuter - dist) / feather).coerceIn(0f, 1f)
        val s = cover * cover * (3f - 2f * cover)
        val c = pixels[idx]
        val a = (((c ushr 24) and 0xFF) * s + 0.5f).toInt().coerceIn(0, 255)
        pixels[idx] = (a shl 24) or (c and 0x00FFFFFF)
    }

    private fun sortByDpi(paths: List<String>): List<String> {
        val targetRank = dpiToRank(options.densityDpi)
        return paths.sortedWith(
            compareBy<String> { path ->
                val lower = path.lowercase()
                val pathRank = DPI_RANK.entries.firstOrNull { (qualifier, _) -> qualifier in lower }?.value
                if (pathRank != null) kotlin.math.abs(pathRank - targetRank)
                else 50
            }.thenByDescending { path ->
                zip.uncompressedSize(path)
            },
        )
    }

    private fun dpiToRank(dpi: Int): Int = when {
        dpi >= 560 -> 0 // xxxhdpi
        dpi >= 400 -> 1 // xxhdpi
        dpi >= 280 -> 2 // xhdpi
        dpi >= 200 -> 3 // hdpi
        dpi >= 140 -> 4 // mdpi
        else -> 5
    }

    private fun attr(node: XmlNode, name: String): String? {
        for (attribute in node.attributes) {
            val attrName = attribute.name
            val matches = attrName == name ||
                attrName.endsWith(":$name") ||
                (attrName.endsWith(name) && (attrName.length == name.length || attrName[attrName.length - name.length - 1] == ':'))
            if (!matches) continue
            val tv = attribute.typedValue
            if (tv != null) {
                return when (tv.type) {
                    BinaryXmlParser.TYPE_REFERENCE ->
                        "@${tv.data.toUInt().toString(16).padStart(8, '0')}"
                    BinaryXmlParser.TYPE_STRING ->
                        attribute.rawValue ?: tv.data.toString()
                    TYPE_FLOAT -> "0x${tv.data.toUInt().toString(16)}"
                    TYPE_INT_COLOR_ARGB8 ->
                        "#${tv.data.toUInt().toString(16).padStart(8, '0')}"
                    TYPE_INT_COLOR_RGB8 ->
                        "#ff${(tv.data and 0xFFFFFF).toUInt().toString(16).padStart(6, '0')}"
                    BinaryXmlParser.TYPE_INT_DEC, BinaryXmlParser.TYPE_INT_HEX -> tv.data.toString()
                    else -> attribute.rawValue?.takeIf { it.isNotBlank() } ?: tv.data.toString()
                }
            }
            attribute.rawValue?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return null
    }

    private fun uniformColorBitmap(argb: Int): RgbaBitmap =
        RgbaBitmap(1, 1, intArrayOf(argb), uniformArgb = argb)

    private fun trace(msg: String) {
        if (verbose) println("[OpenIconRenderer] $msg")
    }

    internal data class ExtractResult(val bitmap: RgbaBitmap, val sourcePath: String?) {
        fun toPng(): ByteArray = PngEncoder.encode(bitmap)
    }

    companion object {
        private const val TYPE_FLOAT = 0x04
        private const val TYPE_INT_COLOR_ARGB8 = 0x1c
        private const val TYPE_INT_COLOR_RGB8 = 0x1d

        private val DPI_RANK = linkedMapOf(
            "xxxhdpi" to 0,
            "xxhdpi" to 1,
            "xhdpi" to 2,
            "hdpi" to 3,
            "mdpi" to 4,
            "ldpi" to 5,
            "anydpi" to 1, // treat as matching xxhdpi-ish for ranking near target
            "nodpi" to 7,
        )

        // Cached row-span circle mask (clear outside + feather rim only; interior untouched).
        private var cachedCircleRows: CircleMaskRows? = null

        private class CircleMaskRows(
            val size: Int,
            val cx: Float,
            val rOuter: Float,
            val feather: Float,
            /** Per row: xLeftOuter, xRightOuter, xLeftInner, xRightInner; xLeftOuter=-1 => empty. */
            val spans: IntArray,
            val dy2: FloatArray,
        )

        private fun circleMaskRows(size: Int): CircleMaskRows {
            val hit = cachedCircleRows
            if (hit != null && hit.size == size) return hit
            val built = buildCircleRows(size, size, size)
            cachedCircleRows = built
            return built
        }

        private fun buildCircleRows(diameter: Int, width: Int, height: Int): CircleMaskRows {
            val cx = (width - 1) * 0.5f
            val cy = (height - 1) * 0.5f
            val radius = diameter * 0.5f
            val feather = 1.5f
            val rOuter = radius + 0.5f
            val rInner = rOuter - feather
            val rOuter2 = rOuter * rOuter
            val rInner2 = if (rInner > 0f) rInner * rInner else 0f
            val spans = IntArray(height * 4)
            val dy2Arr = FloatArray(height)
            for (y in 0 until height) {
                val base = y * 4
                val dy = y - cy
                val dy2 = dy * dy
                dy2Arr[y] = dy2
                if (dy2 >= rOuter2) {
                    spans[base] = -1
                    continue
                }
                val maxDxOuter = sqrt(rOuter2 - dy2)
                val xLeftOuter = ceil(cx - maxDxOuter).toInt().coerceAtLeast(0)
                val xRightOuter = floor(cx + maxDxOuter).toInt().coerceAtMost(width - 1)
                spans[base] = xLeftOuter
                spans[base + 1] = xRightOuter
                if (dy2 >= rInner2) {
                    // No opaque interior: mark inverted inner range.
                    spans[base + 2] = xRightOuter + 1
                    spans[base + 3] = xLeftOuter - 1
                } else {
                    val maxDxInner = sqrt(rInner2 - dy2)
                    spans[base + 2] = ceil(cx - maxDxInner).toInt().coerceAtLeast(xLeftOuter)
                    spans[base + 3] = floor(cx + maxDxInner).toInt().coerceAtMost(xRightOuter)
                }
            }
            return CircleMaskRows(diameter, cx, rOuter, feather, spans, dy2Arr)
        }
    }
}
