package online.coffeeispower.crema.utils.fds

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Minimal FFM bindings for the handful of libc fd operations that the fd
 * reactor and its consumers need beyond epoll: dup'ing fds, flipping them to
 * non-blocking mode, draining eventfd-style fds and closing them.
 */
object Posix {

    private const val F_GETFL = 3
    private const val F_SETFL = 4

    /** O_NONBLOCK on Linux (independent of the arch). */
    private const val O_NONBLOCK = 0x800

    /** Size in bytes of the eventfd counter read to drain a sync-fd. */
    private const val EVENTFD_SIZE = 8L

    /** PROT_READ for [mmapRead]. */
    private const val PROT_READ = 0x1

    /** MAP_SHARED for [mmapRead]. */
    private const val MAP_SHARED = 0x01

    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private val fcntlHandle = linker.downcallHandle(
        lookup.findOrThrow("fcntl"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG),
    )
    private val dupHandle = linker.downcallHandle(
        lookup.findOrThrow("dup"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val readHandle = linker.downcallHandle(
        lookup.findOrThrow("read"),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )
    private val closeHandle = linker.downcallHandle(
        lookup.findOrThrow("close"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val openHandle = linker.downcallHandle(
        lookup.findOrThrow("open"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT),
    )
    private val mmapHandle = linker.downcallHandle(
        lookup.findOrThrow("mmap"),
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_LONG,
        ),
    )
    private val munmapHandle = linker.downcallHandle(
        lookup.findOrThrow("munmap"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG),
    )

    /** `fcntl(fd, F_GETFL)`. */
    fun getFileFlags(fd: Int): Int = fcntlHandle.invoke(fd, F_GETFL, 0L) as Int

    /** `fcntl(fd, F_SETFL, flags)`. */
    fun setFileFlags(fd: Int, flags: Int) {
        fcntlHandle.invoke(fd, F_SETFL, flags.toLong())
    }

    /** Sets O_NONBLOCK on [fd], keeping its existing file status flags. */
    fun setNonBlocking(fd: Int) {
        setFileFlags(fd, getFileFlags(fd) or O_NONBLOCK)
    }

    /** `dup(fd)`, returning a new descriptor referring to the same open file. */
    fun dup(fd: Int): Int = dupHandle.invoke(fd) as Int

    /** `open(path, flags)`, returning the new descriptor or -1 on error. */
    fun open(path: String, flags: Int): Int {
        Arena.ofConfined().use { arena ->
            val cPath = arena.allocateFrom(path)
            return openHandle.invoke(cPath, flags) as Int
        }
    }

    /**
     * Reads and discards up to [byteSize] bytes from [fd], blocking until at
     * least one byte is available. Returns the number of bytes read, or -1 on
     * error.
     */
    fun drain(fd: Int, byteSize: Long = EVENTFD_SIZE): Long {
        Arena.ofConfined().use { arena ->
            val buffer = arena.allocate(byteSize)
            return readHandle.invoke(fd, buffer, byteSize) as Long
        }
    }

    /** `close(fd)`. */
    fun close(fd: Int) {
        closeHandle.invoke(fd)
    }

    /** `mmap(fd, length)` read-only (PROT_READ, MAP_SHARED), copies [length] bytes into a fresh [ByteArray]. */
    fun mmapRead(fd: Int, length: Long): ByteArray {
        // mmap's addr hint must be a real segment, not null: this JDK's FFM
        // downcall rejects null for ADDRESS arguments, and mmap ignores the
        // hint anyway.
        val addr = mmapHandle.invoke(Arena.global().allocate(0L), length, PROT_READ, MAP_SHARED, fd, 0L) as MemorySegment
        val address = addr.address()
        if (address == -1L) error("mmap(fd=$fd, length=$length) failed")
        Arena.ofConfined().use { arena ->
            val segment = MemorySegment.ofAddress(address).reinterpret(length, arena, null)
            return segment.toArray(ValueLayout.JAVA_BYTE).also {
                munmapHandle.invoke(segment, length)
            }
        }
    }
}
