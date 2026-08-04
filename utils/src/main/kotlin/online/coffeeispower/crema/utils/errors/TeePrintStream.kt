package online.coffeeispower.crema.utils.errors

import java.io.FileOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * A [PrintStream] that forwards every write to [delegate] (typically
 * `System.err`, i.e. the TTY) and, when [logFile] can be opened, appends the
 * same bytes to it. Used at crash time so the session log captures the final
 * panic even though it is not routed through log4j.
 *
 * Never throws: if the log file cannot be opened, the tee silently degrades to
 * the delegate only.
 */
class TeePrintStream(
    private val delegate: PrintStream,
    logFile: Path,
) : PrintStream(
    TeeOutputStream(delegate, tryOpen(logFile)),
    true,
    StandardCharsets.UTF_8,
) {
    companion object {
        private fun tryOpen(logFile: Path): OutputStream? = try {
            FileOutputStream(logFile.toFile(), /* append = */ true)
        } catch (e: Exception) {
            null
        }
    }
}

private class TeeOutputStream(
    private val delegate: OutputStream,
    private val file: OutputStream?,
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        file?.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        file?.write(b, off, len)
    }

    override fun flush() {
        delegate.flush()
        file?.flush()
    }

    override fun close() {
        delegate.close()
        file?.close()
    }
}
