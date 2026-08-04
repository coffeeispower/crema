// The settings file is the entry point of every Gradle build.
// Its primary purpose is to define the subprojects.
// It is also used for some aspects of project-wide configuration, like managing plugins, dependencies, etc.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

dependencyResolutionManagement {
    // Use Maven Central as the default repository (where Gradle will download dependencies) in all subprojects.
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Use the Foojay Toolchains plugin to automatically download JDKs required by subprojects.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Include the `app` and `utils` subprojects in the build.
// If there are changes in only one of the projects, Gradle will rebuild only the one that has changed.
// Learn more about structuring projects with Gradle - https://docs.gradle.org/8.7/userguide/multi_project_builds.html
include(":app")
include(":utils")
include(":core")
include(":vulkan-renderer")
include(":lwjgl-utils")
include(":drm-sys")
include(":blit-targets-drm")
include(":blit-targets-win32")
include(":blit-targets-wayland")

rootProject.name = "jayland"
project(":app").name = "jayland"
project(":vulkan-renderer").name = "jayland-vulkan-renderer"
project(":utils").name = "jayland-utils"
project(":core").name = "jayland-core"
project(":lwjgl-utils").name = "jayland-lwjgl-utils"
project(":drm-sys").name = "jayland-drm-sys"
project(":blit-targets-drm").name = "jayland-blit-targets-drm"
project(":blit-targets-win32").name = "jayland-blit-targets-win32"
project(":blit-targets-wayland").name = "jayland-blit-targets-wayland"
