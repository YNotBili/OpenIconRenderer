val openIconGroup = providers.gradleProperty("openiconrenderer.group").get()
val openIconVersion = providers.gradleProperty("openiconrenderer.version").get()

allprojects {
    group = openIconGroup
    version = openIconVersion

    repositories {
        mavenCentral()
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
}
