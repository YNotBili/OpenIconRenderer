plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":open-icon-renderer"))
}

application {
    mainClass = "com.lingmarket.openiconrenderer.benchmark.BenchmarkMainKt"
}

tasks.named<JavaExec>("run") {
    jvmArgs("-Xms512m", "-Xmx2g")
}

tasks.register<JavaExec>("runRelease") {
    group = "application"
    description = "Run benchmark with release-optimized Kotlin/JVM bytecode"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.lingmarket.openiconrenderer.benchmark.BenchmarkMainKt")
    jvmArgs("-Xms512m", "-Xmx2g", "-server")
    dependsOn(tasks.named("compileKotlin"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions {
        suppressWarnings.set(true)
    }
}
