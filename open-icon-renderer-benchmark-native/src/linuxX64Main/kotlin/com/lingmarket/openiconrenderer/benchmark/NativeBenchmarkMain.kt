package com.lingmarket.openiconrenderer.benchmark

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.IconMask
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import kotlin.time.TimeSource

private fun extractOptions(
    size: Int,
    preferAdaptive: Boolean,
    verbose: Boolean = false,
    isolateForeground: Boolean = false,
    mask: IconMask = IconMask.CIRCLE,
) = IconExtractOptions(
    outputSize = size,
    densityDpi = 480,
    sdkVersion = 35,
    preferAdaptive = preferAdaptive,
    mask = mask,
    verbose = verbose,
    isolateForeground = isolateForeground,
)

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "dump" -> {
            val apkPath = args.getOrNull(1) ?: error("Usage: ... dump <apk> <outDir>")
            val outDir = args.getOrNull(2) ?: error("Usage: ... dump <apk> <outDir>")
            dumpIcons(apkPath, outDir)
            return
        }
        // Once-shot CLI ops for Zephyr-aligned hyperfine --shell=none (includes process startup).
        "inspect" -> {
            val apkPath = args.getOrNull(1) ?: error("Usage: ... inspect <apk>")
            onceInspect(apkPath)
            return
        }
        "meta" -> {
            val apkPath = args.getOrNull(1) ?: error("Usage: ... meta <apk>")
            val preview = OpenIconRenderer.parseApkPreview(apkPath)
                ?: error("parseApkPreview failed")
            val m = preview.metadata
            println(
                "pkg=${m.packageName} label=${m.applicationLabel} " +
                    "v=${m.versionName}(${m.versionCode}) sdk=${m.minSdk}/${m.targetSdk} " +
                    "abis=${m.architectures} iconBytes=${preview.iconPng?.size ?: 0}",
            )
            return
        }
        "render" -> {
            val apkPath = args.getOrNull(1)
                ?: error("Usage: ... render <apk> <size> [out.png] [--stages] [--isolate] [--no-mask]")
            val size = args.getOrNull(2)?.toIntOrNull()
                ?: error("Usage: ... render <apk> <size> [out.png] [--stages] [--isolate] [--no-mask]")
            val rest = args.drop(3)
            val stages = rest.contains("--stages")
            val isolate = rest.contains("--isolate")
            val noMask = rest.contains("--no-mask")
            val outPath = rest.firstOrNull { !it.startsWith("--") } ?: "/dev/null"
            onceRender(
                apkPath, size, outPath,
                profileStages = stages,
                isolateForeground = isolate,
                mask = if (noMask) IconMask.NONE else IconMask.CIRCLE,
            )
            return
        }
        "stages" -> {
            // Convenience: render with stage profile to stdout (still writes PNG).
            val apkPath = args.getOrNull(1) ?: error("Usage: ... stages <apk> <size>")
            val size = args.getOrNull(2)?.toIntOrNull() ?: error("Usage: ... stages <apk> <size>")
            onceRender(apkPath, size, "/tmp/open-icon-renderer-stages.png", profileStages = true)
            return
        }
    }
    val apkPath = args.firstOrNull()
        ?: error(
            "Usage:\n" +
                "  open-icon-renderer-benchmark-native <apk> [warmup] [iterations]\n" +
                "  open-icon-renderer-benchmark-native inspect <apk>\n" +
                "  open-icon-renderer-benchmark-native render <apk> <size> [out.png]\n" +
                "  open-icon-renderer-benchmark-native dump <apk> <outDir>",
        )
    val apkBytes = readAllBytes(apkPath) ?: error("Failed to read APK: $apkPath")
    val warmup = args.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 2
    val iterations = args.getOrNull(2)?.toIntOrNull()?.coerceAtLeast(1) ?: 5
    runMode(apkBytes, apkPath, preferAdaptive = true, label = "adaptive-vector", warmup, iterations)
}

private fun onceInspect(apkPath: String) {
    // Path API: mmap + on-demand pages (no full-APK heap copy).
    val inspection = OpenIconRenderer.inspectLauncherIcon(apkPath)
        ?: error("inspect failed")
    if (inspection.resolvedPath.isNullOrEmpty() && inspection.iconRef.isNullOrEmpty()) {
        error("empty inspection")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun onceRender(
    apkPath: String,
    size: Int,
    outPath: String,
    profileStages: Boolean = false,
    isolateForeground: Boolean = false,
    mask: IconMask = IconMask.CIRCLE,
) {
    val result = OpenIconRenderer.extractLauncherIcon(
        apkPath,
        extractOptions(size, preferAdaptive = true, isolateForeground = isolateForeground, mask = mask)
            .copy(profileStages = profileStages),
    ) ?: error("render failed at $size")
    require(result.pngBytes.isNotEmpty()) { "empty png at $size" }
    writeAllBytes(outPath, result.pngBytes)
}

@OptIn(ExperimentalForeignApi::class)
private fun runMode(
    apkBytes: ByteArray,
    apkPath: String,
    preferAdaptive: Boolean,
    label: String,
    warmup: Int,
    iterations: Int,
) {
    println("--- $label (preferAdaptive=$preferAdaptive) ---")
    println("In-process Monotonic (excludes process startup / full APK re-read).")
    println("For Zephyr-aligned CLI: use inspect/render + hyperfine --shell=none (task runHyperfineShellNone).")
    println("Zephyr ref (Ryzen 7700, hyperfine shell=none, circle): inspect=13.9 512=37.6 1024=68.3 2048=166.0 ms")
    val inspection = OpenIconRenderer.inspectLauncherIcon(apkBytes)
    println("Resolved: iconRef=${inspection?.iconRef} path=${inspection?.resolvedPath}")
    val probe = OpenIconRenderer.extractLauncherIcon(
        apkBytes,
        extractOptions(512, preferAdaptive, verbose = true),
    )
    println(
        "Probe render: ${if (probe == null) "FAILED" else "ok ${probe.pngBytes.size} bytes source=${probe.sourcePath}"}",
    )
    println("Warmup: $warmup  Iterations: $iterations")
    println()

    repeat(warmup) {
        OpenIconRenderer.inspectLauncherIcon(apkBytes)
        OpenIconRenderer.extractLauncherIconPng(apkBytes, extractOptions(512, preferAdaptive))
    }

    val inspect = benchmarkTask("inspect apk icon", iterations) {
        OpenIconRenderer.inspectLauncherIcon(apkBytes)
    }
    printResult(inspect)

    for (size in listOf(512, 1024, 2048)) {
        val result = benchmarkTask("render ${size}x$size", iterations) {
            val png = OpenIconRenderer.extractLauncherIconPng(apkBytes, extractOptions(size, preferAdaptive))
            require(png != null && png.isNotEmpty()) { "render failed at $size ($label)" }
            png.size
        }
        printResult(result)
    }

    // Session: record once, render many sizes (Graphite Recording analogue).
    OpenIconRenderer.openSession(apkBytes)?.use { session ->
        session.record(extractOptions(512, preferAdaptive))
            ?: error("session record failed")
        for (size in listOf(512, 1024, 2048)) {
            val result = benchmarkTask("session-render ${size}x$size", iterations) {
                val out = session.extract(extractOptions(size, preferAdaptive))
                require(out != null && out.pngBytes.isNotEmpty()) { "session render failed at $size" }
                out.pngBytes.size
            }
            printResult(result)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAllBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    return try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val sizeLong = ftell(file)
        if (sizeLong < 0) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val size = sizeLong.toInt()
        if (size.toLong() != sizeLong) return null
        val buf = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = buf.usePinned { pinned ->
                fread(pinned.addressOf(offset), 1.convert(), (size - offset).convert(), file).toInt()
            }
            if (n <= 0) break
            offset += n
        }
        if (offset != size) null else buf
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAllBytes(path: String, bytes: ByteArray) {
    val file = fopen(path, "wb") ?: error("cannot write $path")
    try {
        bytes.usePinned { pinned ->
            val n = platform.posix.fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
            require(n == bytes.size) { "short write $n/${bytes.size} -> $path" }
        }
    } finally {
        fclose(file)
    }
}

private data class BenchResult(
    val name: String,
    val iterations: Int,
    val minMs: Double,
    val avgMs: Double,
    val stdMs: Double,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

private fun benchmarkTask(name: String, iterations: Int, block: () -> Any?): BenchResult {
    val samples = LongArray(iterations)
    val markSource = TimeSource.Monotonic
    for (i in 0 until iterations) {
        var sink: Any? = null
        val start = markSource.markNow()
        sink = block()
        samples[i] = start.elapsedNow().inWholeNanoseconds
        sink?.let { }
    }
    val ms = samples.map { it / 1_000_000.0 }
    val sorted = ms.sorted()
    val avg = ms.average()
    val variance = ms.map { val d = it - avg; d * d }.average()
    return BenchResult(
        name = name,
        iterations = iterations,
        minMs = sorted.first(),
        avgMs = avg,
        stdMs = kotlin.math.sqrt(variance),
        p50Ms = percentile(sorted, 0.50),
        p95Ms = percentile(sorted, 0.95),
        maxMs = sorted.last(),
    )
}

private fun percentile(sortedMs: List<Double>, p: Double): Double {
    if (sortedMs.isEmpty()) return 0.0
    val index = ((sortedMs.size - 1) * p).toInt().coerceIn(0, sortedMs.lastIndex)
    return sortedMs[index]
}

private fun printResult(result: BenchResult) {
    println(
        buildString {
            append(result.name.padEnd(22))
            append("  mean=")
            append(formatMs(result.avgMs))
            append("  ±")
            append(formatMs(result.stdMs).trim())
            append("  p50=")
            append(formatMs(result.p50Ms))
            append("  min=")
            append(formatMs(result.minMs))
            append("  max=")
            append(formatMs(result.maxMs))
        },
    )
}

private fun formatMs(value: Double): String {
    val scaled = (value * 100.0).toLong() / 100.0
    val intPart = scaled.toLong()
    var frac = ((scaled - intPart) * 100.0).toLong()
    if (frac < 0) frac = 0
    if (frac > 99) frac = 99
    val fracStr = if (frac < 10) "0$frac" else "$frac"
    return "${intPart.toString().padStart(8, ' ')}.$fracStr ms"
}
