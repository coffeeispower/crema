plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.logging")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":drm-sys"))
    implementation(project(":utils"))
}