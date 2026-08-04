package online.coffeeispower.crema.utils.fds

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.StructLayout
import java.lang.foreign.ValueLayout

/**
 * Minimal FFM bindings for the Linux epoll(7) API, used by [PollDispatcher].
 *
 * Only the operations needed to watch raw file descriptors for readability are
 * bound: `epoll_create1`, `epoll_ctl`, `epoll_wait` and `close`.
 */
internal object Epoll {

    /** EPOLLIN. */
    const val IN = 0x001

    /** EPOLLOUT. */
    const val OUT = 0x004

    /** EPOLLERR, always reported by epoll_wait regardless of the registered mask. */
    const val ERR = 0x008

    /** EPOLLET (edge-triggered). */
    const val ET = 0x80000000.toInt()

    const val CTL_ADD = 1
    const val CTL_MOD = 2
    const val CTL_DEL = 3

    const val MAX_EVENTS = 64

    /**
     * `struct epoll_event { uint32_t events; uint64_t data; }`.
     *
     * On both x86-64 and aarch64 `data` is 8-byte aligned, so `events` is
     * followed by 4 bytes of padding and the struct is 16 bytes.
     */
    val EVENT: StructLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("events"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.JAVA_LONG.withName("data"),
    )

    private val linker = Linker.nativeLinker()
    private val lookup = linker.defaultLookup()

    private val createHandle = linker.downcallHandle(
        lookup.findOrThrow("epoll_create1"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )
    private val ctlHandle = linker.downcallHandle(
        lookup.findOrThrow("epoll_ctl"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
        ),
    )
    private val waitHandle = linker.downcallHandle(
        lookup.findOrThrow("epoll_wait"),
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT,
        ),
    )
    private val closeHandle = linker.downcallHandle(
        lookup.findOrThrow("close"),
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT),
    )

    /** Creates a new epoll instance. */
    fun create(flags: Int): Int = createHandle.invoke(flags) as Int

    /** Adds [fd] to the interest list of [epfd]. */
    fun ctlAdd(epfd: Int, fd: Int, events: Int, data: Long) {
        Arena.ofConfined().use { arena ->
            val event = arena.allocate(EVENT)
            event.set(ValueLayout.JAVA_INT, 0L, events)
            event.set(ValueLayout.JAVA_LONG, 8L, data)
            check(ctlHandle.invoke(epfd, CTL_ADD, fd, event) as Int == 0) {
                "epoll_ctl(EPOLL_CTL_ADD) failed for fd $fd"
            }
        }
    }

    /** Removes [fd] from the interest list of [epfd]. */
    fun ctlDel(epfd: Int, fd: Int) {
        ctlHandle.invoke(epfd, CTL_DEL, fd, MemorySegment.NULL)
    }

    /**
     * Waits for events on [epfd], writing up to [maxEvents] results into
     * [events]. Blocks until at least one fd is ready or [timeout] (ms)
     * elapses; a negative timeout blocks indefinitely. Returns the number of
     * ready fds, or -1 on failure (e.g. EINTR).
     */
    fun wait(epfd: Int, events: MemorySegment, maxEvents: Int, timeout: Int): Int =
        waitHandle.invoke(epfd, events, maxEvents, timeout) as Int

    /** Closes an epoll file descriptor. */
    fun close(epfd: Int) {
        closeHandle.invoke(epfd)
    }
}
