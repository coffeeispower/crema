package online.coffeeispower.crema.utils.logging

import online.coffeeispower.crema.utils.errors.CrashReport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * Minecraft-style session log rotation.
 *
 * Each startup, the previous session's `crema.log` is gzipped into a
 * numbered archive (`crema.log.1.gz`, `crema.log.2.gz`, ...) and the next
 * session starts with a fresh file at the normal path, so the active log
 * always covers exactly one session while the history accumulates cleanly
 * next to it — instead of a single file that grows forever.
 *
 * The active path is [CrashReport.logFile] — the same single source of truth
 * log4j2 resolves `${cremaLogFile:path}` from. Call [archivePreviousSession]
 * as the very first thing in `main`, before any logging happens, so the
 * previous session's file is archived before log4j2 (re)creates it.
 */
object LogArchiver {

    /**
     * Archives the previous session's log if one exists and is non-empty.
     *
     * Archives are named `<logName>.<n>.gz` in the same directory, where `n`
     * is the lowest free number, so they never overwrite each other. Nothing
     * happens if the log is missing or empty. Rotation problems are printed
     * to stderr and swallowed — startup must not fail over log housekeeping.
     */
    fun archivePreviousSession() {
        val logFile = CrashReport.logFile
        if (!Files.exists(logFile)) return
        try {
            if (Files.size(logFile) == 0L) return
            val target = nextArchivePath(logFile)
            Files.newOutputStream(target).use { raw ->
                GZIPOutputStream(raw).use { gz ->
                    Files.newInputStream(logFile).use { input -> input.copyTo(gz) }
                }
            }
            Files.delete(logFile)
        } catch (e: IOException) {
            System.err.println("[crema] could not archive previous log '$logFile': ${e.message}")
        }
    }

    private fun nextArchivePath(logFile: Path): Path {
        val dir = logFile.parent ?: Path.of(".")
        val name = logFile.fileName.toString()
        var index = 1
        while (Files.exists(dir.resolve("$name.$index.gz"))) index++
        return dir.resolve("$name.$index.gz")
    }
}
