rootProject.name = "open-icon-renderer-build"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

include(":open-icon-renderer")
include(":open-icon-renderer-benchmark")
include(":open-icon-renderer-benchmark-native")
