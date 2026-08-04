plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.logging")
}

dependencies {
    implementation(project(":jayland-core"))
    implementation(project(":jayland-drm-sys"))
    implementation(project(":jayland-utils"))
}