package online.coffeeispower.crema.utils.fds

import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel

/**
 * A single-threaded epoll reactor for raw file descriptors, bridged to
 * coroutines through [FdPoller]s.
 *
 * A dedicated thread blocks in `epoll_wait`; whenever a watched fd becomes
 * readable, the corresponding poller's channel receives a wakeup. Registering
 * and unregistering fds is thread-safe and may be done from any thread, so
 * consumers get true suspension points that integrate with
 * `kotlinx.coroutines.select`.
 *
 * Watched fds must be set to non-blocking mode (O_NONBLOCK) by the caller, and
 * consumers must drain pending data/events after each wakeup, otherwise the
 * level-triggered epoll keeps signalling.
 */
class PollDispatcher(
    private val name: String = "crema-poll",
) : AutoCloseable {

    private val channels = ConcurrentHashMap<Int, Channel<Unit>>()

    @Volatile
    private var closed = false

    private val epollFd = Epoll.create(0)
    private val thread = Thread(::run, name).apply {
        isDaemon = true
        start()
    }

    /**
     * Bounded epoll_wait timeout so the loop can observe [closed] on shutdown.
     * [Thread.interrupt] does not reliably wake a thread blocked in an FFM
     * downcall to `epoll_wait`, so we cannot rely on it to terminate the loop.
     */
    private val waitTimeoutMillis = 100

    /**
     * Registers [fd] for readability and returns a [FdPoller] to await on.
     *
     * @throws IllegalStateException if this dispatcher is closed or [fd] is
     *   already being watched.
     */
    fun watch(fd: Int): FdPoller {
        check(!closed) { "PollDispatcher is already closed" }
        val channel = Channel<Unit>(Channel.CONFLATED)
        val existing = channels.putIfAbsent(fd, channel)
        check(existing == null) { "fd $fd is already being watched" }
        Epoll.ctlAdd(epollFd, fd, Epoll.IN, fd.toLong())
        return FdPoller(fd, this, channel)
    }

    internal fun unwatch(fd: Int, channel: Channel<Unit>) {
        if (channels.remove(fd, channel)) {
            Epoll.ctlDel(epollFd, fd)
            channel.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        thread.join()
        Epoll.close(epollFd)
    }

    private fun run() {
        val arena = Arena.ofConfined()
        arena.use { arena ->
            val events = arena.allocate(Epoll.EVENT, Epoll.MAX_EVENTS.toLong())
            while (!closed) {
                val n = Epoll.wait(epollFd, events, Epoll.MAX_EVENTS, waitTimeoutMillis)
                if (n < 0) {
                    // EINTR or a transient failure; the bounded timeout keeps
                    // this from spinning.
                    continue
                }
                for (i in 0 until n) {
                    val fd = events
                        .asSlice(i.toLong() * Epoll.EVENT.byteSize(), Epoll.EVENT)
                        .get(ValueLayout.JAVA_LONG, 8L)
                        .toInt()
                    channels[fd]?.trySend(Unit)
                }
            }
        }
    }
}
