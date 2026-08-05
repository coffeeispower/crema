package buildsrc

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Compiles every `*.glsl` file in [glslSourceDir] into SPIR-V with `glslc`
 * (shaderc) or `glslangValidator`, one `.spv` per source, into [outputDir].
 *
 * Declared as a real task (not a script lambda) so it stays compatible with the
 * configuration cache: all Gradle API access happens through the injected
 * [ExecOperations] and annotated properties.
 */
abstract class CompileGlslTask : DefaultTask() {

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:InputFiles
    abstract val glslSourceDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val dir = glslSourceDir.get().asFile
        if (!dir.isDirectory) return
        val compiler = resolveShaderCompiler()
        dir.listFiles { f -> f.isFile && f.extension == "glsl" }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { glsl ->
                val out = outputDir.get().file(glsl.name.removeSuffix(".glsl") + ".spv").asFile
                out.parentFile.mkdirs()
                logger.lifecycle("compileSpirv: ${glsl.name} -> $out")
                execOperations.exec {
                    workingDir(dir)
                    commandLine(buildCommandLine(compiler, glsl, out))
                }
            }
    }

    private fun buildCommandLine(compiler: String, glsl: File, out: File): List<String> {
        // .glsl extensions carry no stage info, so pass it explicitly:
        // glslc uses -fshader-stage=<stage>, glslangValidator uses -S <stage>.
        val stage = shaderStage(glsl)
        val stageArgs = if (compiler == "glslc") listOf("-fshader-stage=$stage") else listOf("-S", stage)
        return listOf(compiler) + stageArgs + listOf(glsl.name, "-o", out.absolutePath)
    }

    private fun shaderStage(glsl: File): String {
        val match = SHADER_STAGE_PATTERN.find(glsl.name)
            ?: throw GradleException(
                "Cannot infer shader stage from ${glsl.name}; name it <name>.<stage>.glsl, " +
                    "e.g. shape.vert.glsl or shape.frag.glsl",
            )
        return match.groupValues[1]
    }

    companion object {
        private val SHADER_STAGE_PATTERN = Regex("""\.(vert|frag|comp|geom|tesc|tese|mesh|task|rgen|rchit|rmiss|rahit|rcall|rint)\.glsl$""")
    }

    private fun resolveShaderCompiler(): String {
        for (candidate in listOf("glslc", "glslangValidator")) {
            if (canRun(candidate)) return candidate
        }
        throw GradleException(
            "No GLSL-to-SPIR-V compiler found. Install shaderc (glslc) or glslang-tools (glslangValidator) " +
                "to build this module's shaders.",
        )
    }

    private fun canRun(command: String): Boolean = try {
        execOperations.exec {
            commandLine(command, "--version")
            isIgnoreExitValue = true
        }
        true
    } catch (e: Exception) {
        false
    }
}
