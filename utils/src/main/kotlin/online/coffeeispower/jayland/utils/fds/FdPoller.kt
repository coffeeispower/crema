package online.coffeeispower.jayland.utils.fds

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.SelectClause1

/**
 * A waitable that suspends until a raw file descriptor becomes readable.
 *
 * Obtained from [PollDispatcher.watch]. [awaitReadable] is a true suspension
 * point (no thread is blocked while waiting), and [onReadable] exposes the
 * same readiness as a select clause for fanning in multiple fds with
 * `kotlinx.coroutines.select`.
 *
 * The fd must be non-blocking (O_NONBLOCK), and the caller must drain pending
 * data/events after each wakeup since epoll is level-triggered.
 */
class FdPoller internal constructor(
    private val fd: Int,
    private val dispatcher: PollDispatcher,
    private val readable: Channel<Unit>,
) : AutoCloseable {

    /** Suspends until the fd becomes readable, then returns. */
    suspend fun awaitReadable() {
        readable.receive()
    }

    /**
     * Select clause that completes when the fd becomes readable; usable as
     * `select { poller.onReadable { ... } }`.
     */
    val onReadable: SelectClause1<Unit>
        get() = readable.onReceive

    override fun close() {
        dispatcher.unwatch(fd, readable)
    }
}
