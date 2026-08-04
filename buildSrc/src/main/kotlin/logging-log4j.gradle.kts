package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // Facade first (kotlin-logging over SLF4J).
    id("buildsrc.convention.logging")
    // Runs the Log4j2 annotation processor so the custom StrLookup plugin is
    // registered through Log4j2Plugins.dat instead of deprecated package scanning.
    kotlin("kapt")
}

val libs = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")

dependencies {
    // The app is the edge: it provides the Log4j2 backend that SLF4J routes to.
    // Only the `app` module should apply this plugin.
    // compileOnly: log4j-core is needed at compile time for the custom
    // StrLookup plugin (CremaLogFileLookup); runtimeOnly keeps the backend
    // off every other module's classpath. kapt generates the plugin registry.
    kapt(libs.findLibrary("log4jCore").get())
    compileOnly(libs.findLibrary("log4jCore").get())
    runtimeOnly(libs.findLibrary("log4jApi").get())
    runtimeOnly(libs.findLibrary("log4jCore").get())
    runtimeOnly(libs.findLibrary("log4jSlf4j2Impl").get())
}
