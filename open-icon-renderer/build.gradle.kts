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

    // NOTE: Do not force x86-64-v3/AVX feature overrides here when consumed via includeBuild.
    // Mismatched targetCpuFeatures vs the consuming app's Konan baseline can crash LLVM with
    // "Do not know how to split the result of this operator!" during release (and even debug) link.

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
