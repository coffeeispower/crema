package buildsrc

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class DownloadJextractTask : DefaultTask() {

    @get:Input
    abstract val archiveUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:Input
    abstract val trustWithoutChecksum: Property<Boolean>

    @get:Input
    abstract val cacheDir: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun run() {
        val root = File(cacheDir.get())
        if (findJextractBin(root) != null) {
            logger.lifecycle("jextract already cached at $cacheDir")
            return
        }
        root.mkdirs()

        val url = archiveUrl.get()
        val archive = File(root, url.substringAfterLast('/'))
        if (!archive.exists()) {
            logger.lifecycle("Downloading jextract from $url ...")
            execOperations.exec { commandLine("curl", "-fL", "-o", archive.absolutePath, url) }
        }

        val expected = expectedSha256.get()
        if (expected.isNotEmpty()) {
            val actual = sha256(archive)
            check(actual == expected) {
                "jextract checksum mismatch: expected $expected, got $actual"
            }
        } else {
            check(trustWithoutChecksum.get()) {
                "No checksum available for jextract archive and trustWithoutChecksum is disabled."
            }
            logger.lifecycle("jextract archive downloaded without checksum verification (trustWithoutChecksum=true)")
        }

        execOperations.exec {
            workingDir(root)
            commandLine("tar", "-xzf", archive.absolutePath)
        }
        checkNotNull(findJextractBin(root)) {
            "jextract archive extracted but bin/jextract was not found under $cacheDir"
        }
    }
}

fun findJextractBin(root: File): File? {
    val direct = File(root, "bin/jextract")
    if (direct.exists()) return direct
    root.listFiles()?.forEach { child ->
        if (child.isDirectory) {
            findJextractBin(child)?.let { return it }
        }
    }
    return null
}

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
