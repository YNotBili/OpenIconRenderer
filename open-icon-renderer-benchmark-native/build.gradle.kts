plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    linuxX64 {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add(
                    "-Xoverride-konan-properties=" +
                        "targetCpu.linux_x64=x86-64-v3;" +
                        "targetCpuFeatures.linux_x64=" +
                        "+aes,+avx,+avx2,+bmi,+bmi2,+cmov,+crc32,+cx16,+cx8,+fma,+fxsr," +
                        "+lzcnt,+mmx,+movbe,+pclmul,+popcnt,+rdrnd,+rdseed,+sahf," +
                        "+sse,+sse2,+sse3,+sse4.1,+sse4.2,+ssse3,+x87,+xsave,+xsaveopt",
                )
            }
        }
        binaries {
            executable {
                entryPoint = "com.lingmarket.openiconrenderer.benchmark.main"
                linkerOpts("-L/usr/lib", "-L/usr/lib64", "-ldeflate", "-lwebp")
            }
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":open-icon-renderer"))
        }
    }
}

tasks.register("runNativeRelease") {
    group = "application"
    description = "Build and run Kotlin/Native Release in-process benchmark"
    dependsOn("linkReleaseExecutableLinuxX64")
    doLast {
        val exe = layout.buildDirectory
            .file("bin/linuxX64/releaseExecutable/open-icon-renderer-benchmark-native.kexe")
            .get()
            .asFile
        require(exe.exists()) { "Missing native executable: $exe" }
        val apk = project.findProperty("apk")?.toString()
            ?: "/home/rj/lingmarket-back/.tmp/benchmark/fenix.apk"
        val warmup = project.findProperty("warmup")?.toString() ?: "3"
        val iterations = project.findProperty("iterations")?.toString() ?: "7"
        exec {
            commandLine(exe.absolutePath, apk, warmup, iterations)
        }
    }
}

tasks.register("runHyperfineShellNone") {
    group = "application"
    description =
        "Zephyr-aligned CLI bench: hyperfine --shell=none -w5 -r50 (process startup included)"
    dependsOn("linkReleaseExecutableLinuxX64")
    doLast {
        val exe = layout.buildDirectory
            .file("bin/linuxX64/releaseExecutable/open-icon-renderer-benchmark-native.kexe")
            .get()
            .asFile
        require(exe.exists()) { "Missing native executable: $exe" }
        val apk = project.findProperty("apk")?.toString()
            ?: "/home/rj/lingmarket-back/.tmp/benchmark/fenix.apk"
        val outPng = project.findProperty("outPng")?.toString()
            ?: "/tmp/open-icon-renderer-bench.png"
        val warmup = project.findProperty("warmup")?.toString() ?: "5"
        val runs = project.findProperty("iterations")?.toString() ?: "50"
        val script = project.layout.buildDirectory.file("hyperfine-shell-none.sh").get().asFile
        script.parentFile.mkdirs()
        script.writeText(
            """
            #!/usr/bin/env bash
            set -euo pipefail
            EXE=${exe.absolutePath}
            APK=$apk
            OUT=$outPng
            W=$warmup
            R=$runs
            echo "Zephyr-aligned hyperfine --shell=none (warm=${'$'}W n=${'$'}R)"
            echo "EXE=${'$'}EXE"
            echo "APK=${'$'}APK"
            echo "Protocol: density=480 sdk=35 circle mask; process startup included"
            echo "Zephyr ref (Ryzen 7 7700): inspect=13.9  512=37.6  1024=68.3  2048=166.0 ms"
            echo
            # One string per command: hyperfine --shell=none splits argv itself (not multiple commands).
            hyperfine --shell=none --warmup "${'$'}W" --runs "${'$'}R" \
              --export-markdown "${script.parentFile}/hyperfine-shell-none.md" \
              --export-json "${script.parentFile}/hyperfine-shell-none.json" \
              -n "inspect apk icon" "${'$'}EXE inspect ${'$'}APK" \
              -n "render 512x512" "${'$'}EXE render ${'$'}APK 512 ${'$'}OUT" \
              -n "render 1024x1024" "${'$'}EXE render ${'$'}APK 1024 ${'$'}OUT" \
              -n "render 2048x2048" "${'$'}EXE render ${'$'}APK 2048 ${'$'}OUT"
            """.trimIndent() + "\n",
        )
        script.setExecutable(true)
        exec {
            commandLine("bash", script.absolutePath)
        }
    }
}
