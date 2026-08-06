import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

kotlin {
    jvm()
    js {
        browser()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.library()
    }
    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
        binaries.library()
    }

    listOf(
        linuxX64(),
        linuxArm64(),
        macosArm64(),
        mingwX64(),
    ).forEach { target ->
        target.compilations.getByName("main").cinterops {
            create("libdeflate") {
                defFile(project.file("src/nativeInterop/cinterop/libdeflate.def"))
            }
            create("libwebp") {
                defFile(project.file("src/nativeInterop/cinterop/libwebp.def"))
            }
            create("oir_simd") {
                defFile(project.file("src/nativeInterop/cinterop/oir_simd.def"))
                includeDirs(project.file("src/nativeInterop/cinterop"))
            }
        }
        target.binaries.all {
            linkerOpts("-L/usr/lib", "-L/usr/lib64", "-ldeflate", "-lwebp")
        }
    }

    // linuxX64: unlock popcnt / SSE4.2 / AVX2 for LLVM codegen (default Konan baseline is SSE2).
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
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {}
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        val publication = this
        val emptyJavadocJar =
            tasks.register<Jar>("${publication.name}JavadocJar") {
                archiveBaseName.set(providers.provider { publication.artifactId })
                archiveClassifier.set("javadoc")
            }
        artifact(emptyJavadocJar)

        pom {
            name.set("open-icon-renderer")
            description.set("High-performance pure Kotlin APK launcher icon extractor for Kotlin Multiplatform.")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                }
            }
        }
    }
}
