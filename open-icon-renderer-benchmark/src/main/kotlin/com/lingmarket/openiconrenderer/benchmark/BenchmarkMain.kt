package com.lingmarket.openiconrenderer.benchmark

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.IconMask
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.system.measureNanoTime

private fun zephyrOptions(size: Int, verbose: Boolean = false) = IconExtractOptions(
    outputSize = size,
    densityDpi = 480,
    sdkVersion = 35,
    preferAdaptive = true,
    mask = IconMask.CIRCLE,
    verbose = verbose,
)

fun main(args: Array<String>) {
    val apkPath = args.firstOrNull()?.let(Paths::get)
        ?: Paths.get(System.getenv("APK_BENCHMARK_PATH") ?: "fenix.apk")
    require(Files.exists(apkPath)) { "APK not found: $apkPath" }

    val apkBytes = Files.readAllBytes(apkPath)
    val warmup = (args.getOrNull(1)?.toIntOrNull() ?: 2).coerceAtLeast(1)
    val iterations = (args.getOrNull(2)?.toIntOrNull() ?: 5).coerceAtLeast(1)

    println("OpenIconRenderer benchmark (JVM Release)")
    println("APK: $apkPath (${apkBytes.size} bytes)")
    println("Mode: Zephyr-aligned (density=480 sdk=35 preferAdaptive circle mask)")
    println("Warmup: $warmup  Iterations: $iterations")
    println()

    val inspection = OpenIconRenderer.inspectLauncherIcon(apkBytes)
    println("Resolved: iconRef=${inspection?.iconRef} path=${inspection?.resolvedPath}")
    val probe = OpenIconRenderer.extractLauncherIcon(apkBytes, zephyrOptions(512, verbose = true))
    println("Probe render: ${if (probe == null) "FAILED" else "ok ${probe.pngBytes.size} bytes source=${probe.sourcePath}"}")
    println()

    repeat(warmup) {
        OpenIconRenderer.inspectLauncherIcon(apkBytes)
        OpenIconRenderer.extractLauncherIconPng(apkBytes, zephyrOptions(512))
    }

    benchmarkTask("inspect apk icon", iterations) {
        OpenIconRenderer.inspectLauncherIcon(apkBytes)
    }.also { printResult(it) }

    for (size in listOf(512, 1024, 2048)) {
        benchmarkTask("render ${size}x$size", iterations) {
            val png = OpenIconRenderer.extractLauncherIconPng(apkBytes, zephyrOptions(size))
            require(png != null && png.isNotEmpty()) { "render failed at $size" }
            png.size
        }.also { printResult(it) }
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

private fun benchmarkTask(name: String, iterations: Int = 5, block: () -> Any?): BenchResult {
    val samples = LongArray(iterations)
    for (i in 0 until iterations) {
        var sink: Any? = null
        val elapsed = measureNanoTime {
            sink = block()
        }
        samples[i] = elapsed
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
            append("%.2f ms".format(result.stdMs))
            append("  p50=")
            append(formatMs(result.p50Ms))
            append("  min=")
            append(formatMs(result.minMs))
            append("  max=")
            append(formatMs(result.maxMs))
        },
    )
}

private fun formatMs(value: Double): String = "%8.2f ms".format(value)
