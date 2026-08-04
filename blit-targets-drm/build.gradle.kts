plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.logging")
}

dependencies {
    implementation(project(":crema-core"))
    implementation(project(":crema-drm-sys"))
    implementation(project(":crema-utils"))
}