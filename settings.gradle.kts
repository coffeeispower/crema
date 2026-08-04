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

rootProject.name = "crema"
project(":app").name = "crema"
project(":vulkan-renderer").name = "crema-vulkan-renderer"
project(":utils").name = "crema-utils"
project(":core").name = "crema-core"
project(":lwjgl-utils").name = "crema-lwjgl-utils"
project(":drm-sys").name = "crema-drm-sys"
project(":blit-targets-drm").name = "crema-blit-targets-drm"
project(":blit-targets-win32").name = "crema-blit-targets-win32"
project(":blit-targets-wayland").name = "crema-blit-targets-wayland"
