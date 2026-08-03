plugins {
    // Apply the shared build logic from a convention plugin.
    // The shared code is located in `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts`.
    id("buildsrc.convention.kotlin-jvm")
    // Logging facade + Log4j2 backend (the app is the edge that provides the implementation).
    id("buildsrc.convention.logging-log4j")

    // Apply the Application plugin to add support for building an executable JVM application.
    application
}

dependencies {
    implementation(project(":utils"))
    implementation(project(":core"))
    implementation(project(":vulkan-renderer"))
    implementation(project(":blit-targets-drm"))
    implementation(project(":blit-targets-win32"))
    implementation(project(":blit-targets-wayland"))
}

application {
    // Define the Fully Qualified Name for the application main class
    // (Note that Kotlin compiles `App.kt` to a class with FQN `com.example.app.AppKt`.)
    mainClass = "online.coffeeispower.jayland.app.AppKt"

    // FFM/native-access flags so `gradlew run` (and the start scripts from
    // installDist/distZip) work without repeating them on every command line:
    //  - `--enable-native-access=ALL-UNNAMED` unblocks java.lang.foreign access
    //    (jextract-generated libdrm bindings, LWJGL, Posix helpers).
    //  - `--sun-misc-unsafe-memory-access=allow` restores sun.misc.Unsafe memory
    //    access on JDK 23+ (used by some native bridges).
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--sun-misc-unsafe-memory-access=allow",
    )
}
