plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.logging")
}

dependencies {
    implementation(project(":core"))
}
