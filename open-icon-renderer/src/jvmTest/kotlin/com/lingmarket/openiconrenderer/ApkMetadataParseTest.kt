package com.lingmarket.openiconrenderer

import com.lingmarket.openiconrenderer.api.OpenIconRenderer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check against a real release APK.
 *
 * The APK path is resolved from, in order:
 *  1. `-Doir.test.apk=<path>` system property
 *  2. `OIR_TEST_APK=<path>` environment variable
 *  3. `$user.home/BiliTerminal/app/build/outputs/apk/release/` (signed, then unsigned)
 *
 * When no APK is available the test is skipped (not failed) so it stays green on CI and on
 * machines that do not build BiliTerminal.
 *
 * Basic invariants are asserted for any APK. The BiliTerminal release baseline (label, version,
 * SDK levels) is only asserted when the parsed package actually is BiliTerminal, so pointing the
 * override at another app does not produce a confusing failure.
 */
class ApkMetadataParseTest {
    @Test
    fun parseBiliTerminalReleaseApk() {
        val apk = resolveTestApk()
        if (apk == null) {
            println("[skip] parseBiliTerminalReleaseApk: no test APK found.")
            println("       Set -Doir.test.apk=<path> or OIR_TEST_APK=<path>, or build BiliTerminal release.")
            return
        }

        val preview = OpenIconRenderer.parseApkPreview(apk.absolutePath)
        assertNotNull(preview, "parseApkPreview returned null for ${apk.absolutePath}")
        val m = preview.metadata
        println("apk=${apk.name} meta=$m iconBytes=${preview.iconPng?.size}")

        // Invariants that must hold for any well-formed APK.
        assertTrue(m.packageName.isNotBlank(), "empty packageName")
        val label = assertNotNull(m.applicationLabel, "missing applicationLabel")
        assertTrue(label.isNotBlank(), "blank applicationLabel")
        val targetSdk = assertNotNull(m.targetSdk, "missing targetSdk")
        assertTrue(targetSdk > 0, "invalid targetSdk $targetSdk")
        assertTrue(m.architectures.isNotEmpty(), "expected native ABIs, got ${m.architectures}")
        val icon = preview.iconPng
        assertNotNull(icon, "expected a rendered icon PNG")
        assertTrue(icon.isNotEmpty(), "rendered icon PNG is empty")

        if (m.packageName != BILITERMINAL_PACKAGE) {
            println("[note] parsed ${m.packageName}, skipping BiliTerminal baseline assertions")
            return
        }

        // BiliTerminal release baseline. Version numbers are deliberately not asserted: they change
        // with every release (the local build is already 3.1.0-Qx-BETA1) and pinning a snapshot only
        // yields false failures. They are printed for eyeballing instead.
        assertEquals(BILITERMINAL_PACKAGE, m.packageName)
        assertEquals("哔哩终端", m.applicationLabel)
        assertEquals(14, m.minSdk)
        assertEquals(26, m.targetSdk)
        println("baseline ok: versionCode=${m.versionCode} versionName=${m.versionName}")
    }

    private fun resolveTestApk(): File? {
        System.getProperty(APK_PROPERTY)?.let { path ->
            File(path).takeIf(File::isFile)?.let { return it }
            println("[warn] $APK_PROPERTY=$path is not a readable file, falling back")
        }
        System.getenv(APK_ENV)?.let { path ->
            File(path).takeIf(File::isFile)?.let { return it }
            println("[warn] $APK_ENV=$path is not a readable file, falling back")
        }
        val home = System.getProperty("user.home") ?: return null
        val releaseDir = File(home, "BiliTerminal/app/build/outputs/apk/release")
        return listOf("app-release.apk", "app-release-unsigned.apk")
            .asSequence()
            .map { File(releaseDir, it) }
            .firstOrNull(File::isFile)
    }

    private companion object {
        private const val APK_PROPERTY = "oir.test.apk"
        private const val APK_ENV = "OIR_TEST_APK"
        private const val BILITERMINAL_PACKAGE = "com.RobinNotBad.BiliClient"
    }
}
