package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    kotlin("jvm")
}

val libs = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")

dependencies {
    // kotlin-logging facade over SLF4J (compile-scope, usable from any module).
    // The runtime backend (Log4j2) is deliberately NOT added here: only the `app`
    // edge module provides the implementation, so every module stays backend-agnostic.
    implementation(libs.findLibrary("kotlinLogging").get())
    implementation(libs.findLibrary("slf4jApi").get())
}
