package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.IconExtractOptions
import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.sqrt
import kotlin.system.measureNanoTime
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual-style performance smoke for parseApkPreview vs icon extract.
 * Run: `./gradlew :OpenIconRenderer:open-icon-renderer:jvmTest --tests '*PreviewPerfTest'`
 */
class PreviewPerfTest {
    @Test
    fun benchParseApkPreviewAcrossApks() {
        val apks = listOf(
            "/home/alliehe/下载/老李社区 手表版_1.0.1_com.zxi2233.laoli_community_waer_.apk",
            "/home/alliehe/文档/Projects/LingMarket/LingMarket/app/build/outputs/apk/debug/app-debug.apk",
            "/home/alliehe/.local/share/waydroid/data/app/~~nzbdSukXD6bYwXBwqe-GQQ==/com.android.vending-sSgc9GSAjHTrkirzoGiMBg==/base.apk",
            "/home/alliehe/.local/share/waydroid/data/app/~~6nrJLa_Sh7nb7zJn8ygRkQ==/com.openai.chatgpt-mBpNbReelRHpJYH1NHc0kw==/base.apk",
        ).map(Path::of).filter { Files.isRegularFile(it) }

        assertTrue(apks.isNotEmpty(), "no benchmark APKs found")

        val warmup = 2
        val iterations = 5
        val iconOpts192 = IconExtractOptions(outputSize = 192)
        val iconOpts432 = IconExtractOptions(outputSize = 432)

        println()
        println("=== OpenIconRenderer preview performance ===")
        println("warmup=$warmup iterations=$iterations")
        println()

        for (apk in apks) {
            val sizeMb = Files.size(apk) / (1024.0 * 1024.0)
            println("--- ${apk.fileName} (${"%.1f".format(sizeMb)} MiB) ---")

            // Cold-ish first call (after one throwaway for class init)
            OpenIconRenderer.parseApkPreview(apk.toString(), iconOpts192)

            val pathMeta = bench("parseApkMetadata(path)", iterations, warmup) {
                val m = OpenIconRenderer.parseApkMetadata(apk.toString())
                require(m != null && m.packageName.isNotBlank())
                m!!.permissions.size
            }
            printResult(pathMeta)

            val pathPreview = bench("parseApkPreview(path,192)", iterations, warmup) {
                val p = OpenIconRenderer.parseApkPreview(apk.toString(), iconOpts192)
                require(p != null && p.metadata.packageName.isNotBlank())
                p.metadata.permissions.size to (p.iconPng?.size ?: 0)
            }
            printResult(pathPreview)

            val bytes = Files.readAllBytes(apk)
            val bytesPreview = bench("parseApkPreview(bytes,192)", iterations, warmup) {
                val p = OpenIconRenderer.parseApkPreview(bytes, iconOpts192)
                require(p != null && p.metadata.packageName.isNotBlank())
                p.metadata.permissions.size to (p.iconPng?.size ?: 0)
            }
            printResult(bytesPreview)

            val pathIcon192 = bench("extractLauncherIconPng(path,192)", iterations, warmup) {
                val png = OpenIconRenderer.extractLauncherIconPng(apk.toString(), iconOpts192)
                require(png != null && png.isNotEmpty())
                png.size
            }
            printResult(pathIcon192)

            val pathIcon432 = bench("extractLauncherIconPng(path,432)", iterations, warmup) {
                val png = OpenIconRenderer.extractLauncherIconPng(apk.toString(), iconOpts432)
                require(png != null && png.isNotEmpty())
                png.size
            }
            printResult(pathIcon432)

            val preview = OpenIconRenderer.parseApkPreview(apk.toString(), iconOpts192)
            assertNotNull(preview)
            println(
                "  sample: pkg=${preview.metadata.packageName} " +
                    "perms=${preview.metadata.permissions.size} " +
                    "abis=${preview.metadata.architectures} " +
                    "icon=${preview.iconPng?.size ?: 0}B " +
                    "iconRef=${preview.metadata.launcherIconRef}",
            )
            println(
                "  overhead preview@192 vs icon@192: " +
                    "${"%.1f".format(pathPreview.avgMs - pathIcon192.avgMs)} ms avg " +
                    "(${"%.0f".format(100.0 * (pathPreview.avgMs - pathIcon192.avgMs) / pathIcon192.avgMs.coerceAtLeast(0.001))}%)",
            )
            println(
                "  metadata-only vs preview@192: " +
                    "${"%.1f".format(pathMeta.avgMs)} vs ${"%.1f".format(pathPreview.avgMs)} ms avg",
            )
            println()
        }
    }

    private data class Result(
        val name: String,
        val minMs: Double,
        val avgMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val maxMs: Double,
        val stdMs: Double,
    )

    private fun bench(name: String, iterations: Int, warmup: Int, block: () -> Any?): Result {
        repeat(warmup) { block() }
        val samples = DoubleArray(iterations)
        for (i in 0 until iterations) {
            var sink: Any? = null
            val ns = measureNanoTime { sink = block() }
            sink.hashCode()
            samples[i] = ns / 1_000_000.0
        }
        val sorted = samples.sorted()
        val avg = samples.average()
        val std = sqrt(samples.map { val d = it - avg; d * d }.average())
        return Result(
            name = name,
            minMs = sorted.first(),
            avgMs = avg,
            p50Ms = sorted[((sorted.size - 1) * 0.50).toInt()],
            p95Ms = sorted[((sorted.size - 1) * 0.95).toInt()],
            maxMs = sorted.last(),
            stdMs = std,
        )
    }

    private fun printResult(r: Result) {
        println(
            "  %-36s  avg=%6.1f  p50=%6.1f  p95=%6.1f  min=%6.1f  max=%6.1f  ±%.1f ms".format(
                r.name, r.avgMs, r.p50Ms, r.p95Ms, r.minMs, r.maxMs, r.stdMs,
            ),
        )
    }
}
