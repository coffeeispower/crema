package buildsrc.convention

import org.gradle.api.artifacts.VersionCatalogsExtension

val libs = extensions
    .getByType(VersionCatalogsExtension::class.java)
    .named("libs")

val lwjglVersion = libs.findVersion("lwjgl").get().requiredVersion

val lwjglNatives = run {
    val os = System.getProperty("os.name")
    val arch = System.getProperty("os.arch")

    when {
        os == "FreeBSD" ->
            "natives-freebsd"

        os.startsWith("Linux") ||
                os.startsWith("SunOS") ||
                os.startsWith("Unix") -> when {
            arch.startsWith("arm") || arch.startsWith("aarch64") ->
                if (arch.contains("64") || arch.startsWith("armv8"))
                    "natives-linux-arm64"
                else
                    "natives-linux-arm32"

            arch.startsWith("ppc") ->
                "natives-linux-ppc64le"

            arch.startsWith("riscv") ->
                "natives-linux-riscv64"

            else ->
                "natives-linux"
        }

        os.startsWith("Windows") ->
            if (arch.contains("64"))
                if (arch.startsWith("aarch64"))
                    "natives-windows-arm64"
                else
                    "natives-windows"
            else
                "natives-windows-x86"

        os.startsWith("Mac") ->
            if (arch.startsWith("aarch64"))
                "natives-macos-arm64"
            else
                "natives-macos"

        else ->
            error("Unsupported platform: $os ($arch)")
    }
}

dependencies {
    add(
        "implementation",
        platform("org.lwjgl:lwjgl-bom:$lwjglVersion")
    )

    add("implementation", libs.findLibrary("lwjgl").get())
    add("implementation", libs.findLibrary("lwjgl.freetype").get())
    add("implementation", libs.findLibrary("lwjgl.glfw").get())
    add("implementation", libs.findLibrary("lwjgl.harfbuzz").get())
    add("implementation", libs.findLibrary("lwjgl.stb").get())
    add("implementation", libs.findLibrary("lwjgl.vma").get())
    add("implementation", libs.findLibrary("lwjgl.vulkan").get())

    add("implementation", libs.findBundle("joml").get())
    fun native(module: String) =
        "org.lwjgl:$module::$lwjglNatives"

    add("implementation", native("lwjgl"))
    add("implementation", native("lwjgl-freetype"))
    add("implementation", native("lwjgl-glfw"))
    add("implementation", native("lwjgl-harfbuzz"))
    add("implementation", native("lwjgl-stb"))
    add("implementation", native("lwjgl-vma"))
}