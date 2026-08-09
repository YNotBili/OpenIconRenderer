rootProject.name = "open-icon-renderer-build"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":open-icon-renderer")
// Benchmark modules are optional local tooling; keep them out of composite includeBuild
// so lingmarket-back release links are not blocked by benchmark script classpath issues.
// include(":open-icon-renderer-benchmark")
// include(":open-icon-renderer-benchmark-native")
