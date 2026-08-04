package online.coffeeispower.crema.utils.fds

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout

/** Test-only FFM bindings for `pipe(2)`, `write(2)`, `read(2)` and `close(2)`. */
internal object TestLibc {
    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private val pipeHandle = linker.downcallHandle(
        lookup.findOrThrow("pipe"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )
    private val writeHandle = linker.downcallHandle(
        lookup.findOrThrow("write"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    private val readHandle = linker.downcallHandle(
        lookup.findOrThrow("read"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
        ),
    )
    private val closeHandle = linker.downcallHandle(
        lookup.findOrThrow("close"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )

    /** Creates a pipe, returning the read end and the write end. */
    fun pipe(): Pair<Int, Int> = Arena.ofConfined().use { arena ->
        val fds = arena.allocate(ValueLayout.JAVA_INT, 2L)
        check(pipeHandle.invoke(fds) as Int == 0) { "pipe() failed" }
        fds.get(ValueLayout.JAVA_INT, 0L) to fds.get(ValueLayout.JAVA_INT, 4L)
    }

    fun write(fd: Int, value: Byte) {
        Arena.ofConfined().use { arena ->
            val buf = arena.allocate(1L)
            buf.set(ValueLayout.JAVA_BYTE, 0L, value)
            writeHandle.invoke(fd, buf, 1L)
        }
    }

    fun read(fd: Int): Byte = Arena.ofConfined().use { arena ->
        val buf = arena.allocate(1L)
        readHandle.invoke(fd, buf, 1L)
        buf.get(ValueLayout.JAVA_BYTE, 0L)
    }

    fun close(fd: Int) {
        closeHandle.invoke(fd)
    }
}
