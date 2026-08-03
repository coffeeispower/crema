import java.io.File
import buildsrc.DownloadJextractTask
import buildsrc.GenerateDrmBindingsTask
import buildsrc.findJextractBin

plugins {
    id("buildsrc.convention.kotlin-jvm")
}

// DRM/KMS is available on Linux and the BSDs (FreeBSD, OpenBSD, NetBSD, DragonFly).
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()

val isDrmPlatform = osName == "linux" || osName.contains("bsd") || osName.contains("dragonfly")

// os.arch values: "x86_64"/"amd64" -> x64, "aarch64"/"arm64" -> aarch64.
val arch = when {
    osArch in listOf("x86_64", "amd64") -> "x64"
    osArch in listOf("aarch64", "arm64") -> "aarch64"
    else -> osArch
}

val jextractVersion = "25-jextract+2-4"
val jextractBaseUrl = "https://download.java.net/java/early_access/jextract/25/2"
// Official jextract binaries exist only for Linux/macOS/Windows. On the BSDs the Linux x64
// binary can be run under Linuxulator (FreeBSD), or provide your own via
// `-Pjayland.jextractHome=/path/to/jextract` (skips download) or
// `-Pjayland.jextractArchiveUrl=...` (downloads a custom archive).
data class JextractBuild(val archive: String, val sha256: String)

val jextractBuilds = mapOf(
    "linux-x64" to JextractBuild(
        "openjdk-25-jextract+2-4_linux-x64_bin.tar.gz",
        "d0cc481abc1adb16fb9514e1c5e0bfc08d38c29228bece667fb5054ceaffaa42",
    ),
    "linux-aarch64" to JextractBuild(
        "openjdk-25-jextract+2-4_linux-aarch64_bin.tar.gz",
        "0e25e6f6efa042f8758eaec65a873887fd2247fcf2e3e22dcfd7e4179fc8b0ae",
    ),
    "macos-x64" to JextractBuild(
        "openjdk-25-jextract+2-4_macos-x64_bin.tar.gz",
        "6ae7a46e7e7b56f077ab72623c0a894a8d525d5b698c90785b97c241f95a99b1",
    ),
    "macos-aarch64" to JextractBuild(
        "openjdk-25-jextract+2-4_macos-aarch64_bin.tar.gz",
        "3dd1dd1bde059d271739e2cc2290c64f93f85488c86c01e566c0e374eece798f",
    ),
    "windows-x64" to JextractBuild(
        "openjdk-25-jextract+2-4_windows-x64_bin.tar.gz",
        "b03533eb6b249a154752b7c7bf93cdb8c147cf2f9699422e615e84b7fb652872",
    ),
)

fun defaultJextractBuild(): JextractBuild? =
    jextractBuilds["$osName-$arch"]

val defaultCacheRoot = File(System.getProperty("user.home"), ".cache/jayland/jextract/$jextractVersion")
val jextractCacheDir = providers.gradleProperty("jayland.jextractHome")
    .orElse(defaultCacheRoot.absolutePath)

if (!isDrmPlatform) {
    logger.warn("jayland: DRM/KMS is only supported on Linux and the BSDs (current: ${System.getProperty("os.name")}). The `:drm-sys` module will be skipped.")
}

// Header locations per platform family. Overridable for exotic setups.
//   Linux:            headers in /usr/include,       UAPI in /usr/include/libdrm
//   FreeBSD/OpenBSD:  headers in /usr/local/include, UAPI in /usr/local/include/libdrm
//   NetBSD/DragonFly: headers in /usr/pkg/include,   UAPI in /usr/pkg/include/libdrm
val drmHeaderDirProvider = providers.gradleProperty("jayland.drmHeaderDir")
    .orElse(
        when {
            osName == "linux" -> "/usr/include"
            osName in listOf("freebsd", "openbsd") -> "/usr/local/include"
            else -> "/usr/pkg/include"
        }
    )

val libdrmIncludeDirProvider = providers.gradleProperty("jayland.libdrmIncludeDir")
    .orElse("${drmHeaderDirProvider.get()}/libdrm")

val downloadJextract = tasks.register<DownloadJextractTask>("downloadJextract") {
    description = "Downloads and caches the jextract binary (the bindgen equivalent for the FFM API)"
    group = "build setup"

    val customUrl = providers.gradleProperty("jayland.jextractArchiveUrl")
    if (customUrl.isPresent) {
        archiveUrl.set(customUrl)
        expectedSha256.set(providers.gradleProperty("jayland.jextractSha256").orElse(""))
        trustWithoutChecksum.set(true)
    } else {
        val build = checkNotNull(defaultJextractBuild()) {
            "No official jextract binary for os/arch `$osName/$arch`. " +
                "Provide one with `-Pjayland.jextractHome=/path/to/jextract` or `-Pjayland.jextractArchiveUrl=...`."
        }
        archiveUrl.set("$jextractBaseUrl/${build.archive}")
        expectedSha256.set(build.sha256)
        trustWithoutChecksum.set(false)
    }

    cacheDir.set(jextractCacheDir)
}

val generatedDir = layout.buildDirectory.dir("generated/drm")

val generateDrmBindings = tasks.register<GenerateDrmBindingsTask>("generateDrmBindings") {
    description = "Generates libdrm Java bindings from system headers using jextract (bindgen-style, at build time)"
    group = "build setup"
    dependsOn(downloadJextract)
    drmPlatform.set(isDrmPlatform)
    onlyIf("DRM/KMS is only supported on Linux and the BSDs") { drmPlatform.get() }

    jextractBin.set(providers.provider {
        checkNotNull(findJextractBin(File(jextractCacheDir.get()))) {
            "jextract not found under ${jextractCacheDir.get()}; run the downloadJextract task first"
        }
    })
    libdrmIncludeDir.set(libdrmIncludeDirProvider.map { File(it) })
    headers.set(listOf(
        File(drmHeaderDirProvider.get(), "xf86drm.h"),
        File(drmHeaderDirProvider.get(), "xf86drmMode.h"),
    ))
    outputDir.set(generatedDir)
    jdkVersion.set(25)
}

sourceSets.main {
    java.srcDir(generatedDir)
}

tasks.named("compileJava") { dependsOn(generateDrmBindings) }
tasks.named("compileKotlin") { dependsOn(generateDrmBindings) }
