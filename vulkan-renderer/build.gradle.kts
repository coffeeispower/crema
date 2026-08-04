
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
    implementation(project(":crema-utils"))
    implementation(project(":crema-core"))
    implementation(project(":crema-lwjgl-utils"))
    // DrmFormats / drmFourcc: the single source of truth for fourccs and
    // modifiers shared with the KMS side.
    implementation(project(":crema-drm-sys"))

    testImplementation(project(":crema-drm-sys"))

    testRuntimeOnly(libs.log4jApi)
    testRuntimeOnly(libs.log4jCore)
    testRuntimeOnly(libs.log4jSlf4j2Impl)
}