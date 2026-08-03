package buildsrc

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.api.file.DirectoryProperty
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.ExecOperations

abstract class GenerateDrmBindingsTask : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Inject
    abstract val toolchainService: JavaToolchainService

    @get:Input
    abstract val jextractBin: Property<File>

    @get:Input
    abstract val libdrmIncludeDir: Property<File>

    @get:InputFiles
    abstract val headers: ListProperty<File>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val jdkVersion: Property<Int>

    @get:Input
    abstract val drmPlatform: Property<Boolean>

    @TaskAction
    fun run() {
        check(drmPlatform.get()) {
            "DRM/KMS bindings generation is only supported on Linux and the BSDs"
        }
        val bin = jextractBin.get()
        val includeDir = libdrmIncludeDir.get()
        val missing = headers.get().filterNot { it.exists() }
        check(missing.isEmpty()) {
            "Missing libdrm development headers: ${missing.joinToString()}. " +
                "Install libdrm first: Arch `sudo pacman -S libdrm`, FreeBSD/OpenBSD `pkg install libdrm`, " +
                "NetBSD/DragonFly `pkgin install libdrm`, Debian/Ubuntu `sudo apt install libdrm-dev`. " +
                "Override the header locations with `-Pjayland.drmHeaderDir=... -Pjayland.libdrmIncludeDir=...` if needed."
        }

        val launcher = toolchainService.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(jdkVersion.get()))
        }.get()

        execOperations.exec {
            environment("JAVA_HOME", launcher.metadata.installationPath.asFile.absolutePath)
            commandLine(
                bin.absolutePath,
                "--output", outputDir.get().asFile.absolutePath,
                "--target-package", "online.coffeeispower.jayland.drm.sys",
                "--header-class-name", "Xf86Drm",
                "-l", "drm",
                "-I", includeDir.absolutePath,
                *headers.get().map { it.absolutePath }.toTypedArray(),
            )
        }
    }
}
