package online.coffeeispower.jayland.blitTargets.drm

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import online.coffeeispower.jayland.core.Connector
import online.coffeeispower.jayland.core.EventLoop
import online.coffeeispower.jayland.core.EventLoopEvent
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmEventContext
import online.coffeeispower.jayland.utils.fds.PollDispatcher
import online.coffeeispower.jayland.utils.fds.Posix
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.util.concurrent.ConcurrentHashMap

/**
 * The DRM event reactor. [run] blocks on the calling thread, first delivering
 * [EventLoopEvent.StartMonitors], then waiting for any of the cards' fds to
 * become readable and draining it with `drmHandleEvent`. Page flips are routed
 * back to the [DRMCommitter] registered under the flip's `user_data` pointer;
 * every ioctl that may flip is non-blocking, so the reactor never blocks on
 * the compositor's own work. One loop serves all [devices] (one per GPU card)
 * from a single [PollDispatcher]; the committer `user_data` segments are unique
 * across the shared arena, so the same map routes flips regardless of card.
 */
class DRMEventLoop(
    private val devices: List<DRMDevice>,
    private val connectorProvider: () -> List<Connector>,
) : EventLoop {

    private val logger = KotlinLogging.logger {}

    private val committers = ConcurrentHashMap<Long, DRMCommitter>()
    private val userDataArena = Arena.ofShared()

    @Volatile
    private var closed = false

    private var eventContext: MemorySegment = MemorySegment.NULL

    /** Allocates a stable `user_data` segment for [committer] and routes its flips to it. */
    internal fun register(committer: DRMCommitter): MemorySegment {
        val segment = userDataArena.allocate(8L)
        committers[segment.address()] = committer
        return segment
    }

    internal fun unregister(committer: DRMCommitter, userData: MemorySegment) {
        committers.remove(userData.address())
    }

    override fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit) {
        runBlocking {
            handler(EventLoopEvent.StartMonitors(connectorProvider()))
            val arena = Arena.ofConfined()
            try {
                eventContext = allocateEventContext(arena)
                logger.debug { "DRM event reactor running on ${devices.size} card fd(s): ${devices.joinToString { it.fd.toString() }}" }
                devices.forEach { Posix.setNonBlocking(it.fd) }
                PollDispatcher("drm-events").use { dispatcher ->
                    val pollers = devices.map { device -> device.fd to dispatcher.watch(device.fd) }
                    while (!closed) {
                        for ((fd, poller) in pollers) {
                            poller.awaitReadable()
                            drainEvents(fd)
                        }
                    }
                }
            } finally {
                arena.close()
            }
        }
    }

    private fun drainEvents(fd: Int) {
        val ret = Xf86Drm.drmHandleEvent(fd, eventContext)
        if (ret != 0) {
            logger.warn { "drmHandleEvent returned $ret on card fd $fd" }
        }
    }

    private fun allocateEventContext(arena: Arena): MemorySegment {
        val context = _drmEventContext.allocate(arena)
        _drmEventContext.version(context, Xf86Drm.DRM_EVENT_CONTEXT_VERSION())
        _drmEventContext.page_flip_handler(
            context,
            _drmEventContext.page_flip_handler.allocate(::onPageFlip, arena),
        )
        return context
    }

    private fun onPageFlip(fd: Int, sequence: Int, tvSec: Int, tvUsec: Int, userData: MemorySegment) {
        committers[userData.address()]?.onPageFlip(sequence.toLong(), tvSec.toLong(), tvUsec.toLong())
    }

    override fun close() {
        closed = true
        userDataArena.close()
    }
}
