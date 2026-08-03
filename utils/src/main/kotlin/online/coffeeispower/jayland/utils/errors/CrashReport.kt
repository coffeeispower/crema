package online.coffeeispower.jayland.utils.errors

import java.io.PrintStream
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Renders the crash report that accompanies a panic: when it happened, on which
 * system, and — most importantly — where the full session log went, so the user
 * can include it when reporting the crash.
 *
 * [logFile] is the single source of truth for the log path: the app's
 * `log4j2.properties` file appender resolves `${jaylandLogFile:path}`
 * straight from here via the [online.coffeeispower.jayland.app.JaylandLogFileLookup]
 * plugin, so the default is defined exactly once.
 */
object CrashReport {

    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /** Where the app's rolling file appender writes. Override with `JAYLAND_LOG_FILE`. */
    val logFile: Path = System.getenv("JAYLAND_LOG_FILE")?.let { Path.of(it) }
        ?: Path.of(System.getProperty("user.home"), ".local", "share", "jayland", "jayland.log")

    /**
     * Prints a bordered crash report to [out]: a short banner, basic
     * environment diagnostics, and the session log path. Meant to be called
     * from the uncaught exception handler after the throwable's own trace.
     */
    fun print(out: PrintStream, throwable: Throwable) {
        val now = timestampFormat.format(LocalDateTime.now())
        val os = System.getProperty("os.name")
        val osVersion = System.getProperty("os.version")
        val arch = System.getProperty("os.arch")
        val jvm = System.getProperty("java.version")
        val jvmVendor = System.getProperty("java.vendor")

        val lines = listOf(
            "jayland crashed",
            "at $now",
            "OS:  $os $osVersion ($arch)",
            "JVM: $jvm ($jvmVendor)",
            "",
            "The full log of this session was written to:",
            logFile.toString(),
            "",
            "Please include that log file when reporting this crash.",
        )

        val contentWidth = lines.maxOf { it.length }
        val padding = 2
        val innerWidth = contentWidth + padding * 2
        val border = "╔" + "═".repeat(innerWidth) + "╗"
        val bottom = "╚" + "═".repeat(innerWidth) + "╝"

        out.println()
        out.println(border)
        for (line in lines) {
            out.println("║" + " ".repeat(padding) + line.padEnd(contentWidth) + " ".repeat(padding) + "║")
        }
        out.println(bottom)
        out.println()
    }
}
