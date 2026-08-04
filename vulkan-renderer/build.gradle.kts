
plugins {
    // Include kotlin
    id("buildsrc.convention.kotlin-jvm")
    // Logging facade (kotlin-logging over SLF4J).
    id("buildsrc.convention.logging")
    // Include LWJGL (vulkan bindings + joml + stb + glfw + harfbuzz)
    id("buildsrc.convention.lwjgl")
    // Apply Kotlin Serialization plugin from `gradle/libs.versions.toml`.
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(project(":jayland-utils"))
    implementation(project(":jayland-core"))
    implementation(project(":jayland-lwjgl-utils"))
    // DrmFormats / drmFourcc: the single source of truth for fourccs and
    // modifiers shared with the KMS side.
    implementation(project(":jayland-drm-sys"))

    testImplementation(project(":jayland-drm-sys"))

    testRuntimeOnly(libs.log4jApi)
    testRuntimeOnly(libs.log4jCore)
    testRuntimeOnly(libs.log4jSlf4j2Impl)
}