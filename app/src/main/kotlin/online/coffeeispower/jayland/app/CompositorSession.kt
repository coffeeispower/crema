package online.coffeeispower.jayland.app

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import online.coffeeispower.jayland.core.platform.Backend
import online.coffeeispower.jayland.core.monitors.Connector
import online.coffeeispower.jayland.core.platform.EventLoopEvent
import online.coffeeispower.jayland.core.monitors.Output
import java.util.concurrent.CountDownLatch

/**
 * Owns the backend's lifecycle and per-output render loops for the lifetime of
 * the process. It is the glue between the platform event loop and the
 * renderer:
 *
 *  - [startMonitors] launches one render loop per enabled connector, each
 *    calling [renderOneFrame] until its output is detached.
 *  - [shutdown] tears the stack down in dependency order (stop the render
 *    loops, wake and close the event loop, then release the backend) so the
 *    process can exit cleanly from anywhere: Ctrl+C, the panic handler, or a
 *    test runner.
 *
 * The session knows nothing about vulkan or drm: [renderOneFrame] produces one
 * frame (acquire a buffer, record + dispatch, commit, close the submission) and
 * is provided by the caller.
 */
class CompositorSession(
    private val backend: Backend,
    private val renderOneFrame: suspend CoroutineScope.(Output) -> Unit,
) : AutoCloseable {

    private val logger = KotlinLogging.logger {}

    private val renderJobs = HashMap<Connector, Job>()
    private val stoppedConnectors = HashSet<Connector>()

    @Volatile
    private var shuttingDown = false

    @Volatile
    private var started = false

    /**
     * Blocks until the platform event loop exits, then tears the stack down.
     *
     * On a signal (Ctrl+C, SIGTERM) the JVM runs shutdown hooks on their own
     * threads while the main thread is still inside [backend.eventLoop.run].
     * The hook must NOT run [shutdown] itself: the per-output render loops are
     * coroutines of the main thread's event loop, so cancelling them from
     * another thread relies on cross-thread dispatch that can wedge. Instead
     * the hook only wakes the event loop — the main thread's `finally` then
     * runs [shutdown] on the thread the coroutines actually live on — and acts
     * as a watchdog that force-halts the process if that teardown never
     * completes, so the kernel releases the display and restores the console
     * either way.
     */
    fun run() {
        if (started) return
        started = true
        val teardownDone = CountDownLatch(1)
        val hook = Thread({
            shutdown()
            logger.error { "Shutdown watchdog fired; forcing exit" }
            Runtime.getRuntime().halt(1)
        }, "jayland-shutdown").apply { isDaemon = false }
        Runtime.getRuntime().addShutdownHook(hook)
        try {
            backend.eventLoop.run { event -> onEvent(event) }
        } finally {
            shutdown()
            teardownDone.countDown()
            try {
                Runtime.getRuntime().removeShutdownHook(hook)
            } catch (_: IllegalStateException) {
                // The JVM is already shutting down; the hook will call shutdown().
            }
        }
    }

    private suspend fun CoroutineScope.onEvent(event: EventLoopEvent) {
        when (event) {
            is EventLoopEvent.StartMonitors -> startMonitors(event.connectors)
            is EventLoopEvent.MonitorConnected -> {
                stoppedConnectors.remove(event.connector)
                startConnector(event.connector)
            }
            is EventLoopEvent.MonitorDisconnected -> {
                stoppedConnectors.add(event.connector)
                stopConnector(event.connector)
            }
        }
    }

    private fun CoroutineScope.startMonitors(connectors: List<Connector>) {
        connectors.forEach { connector ->
            if (stoppedConnectors.contains(connector)) {
                logger.debug { "Skipping previously stopped connector ${connector.monitor.name}" }
            } else {
                startConnector(connector)
            }
        }
    }

    private fun CoroutineScope.startConnector(connector: Connector) {
        if (shuttingDown) return
        synchronized(renderJobs) {
            if (renderJobs.containsKey(connector)) return
        }
        logger.info { "Starting render loop for connector ${connector.monitor.name}" }
        synchronized(renderJobs) {
            renderJobs[connector] = launch {
                renderOutput(connector)
            }
        }
    }

    private suspend fun stopConnector(connector: Connector) {
        val job = synchronized(renderJobs) { renderJobs.remove(connector) } ?: return
        logger.info { "Stopping render loop for connector ${connector.monitor.name}" }
        job.cancelAndJoin()
    }

    private suspend fun CoroutineScope.renderOutput(connector: Connector) {
        val output = try {
            backend.enable(connector)
        } catch (t: Throwable) {
            // One broken connector must not take the compositor down.
            logger.error(t) { "Failed to enable connector ${connector.monitor.name}" }
            return
        }
        output.use { output ->
            while (!output.detached) {
                try {
                    renderOneFrame(output)
                } catch (e: CancellationException) {
                    // Shutdown: never swallow cancellation, or cancelAndJoin in
                    // shutdown() would wait on a loop that keeps going forever.
                    throw e
                } catch (t: Throwable) {
                    // One bad frame must not kill the render loop. The committer
                    // already returned the frame's buffer to the swapchain, so
                    // retrying cannot starve the pool; log and try the next one.
                    logger.error(t) { "Frame failed on output ${connector.monitor.name}; continuing" }
                }
            }
        }
    }

    fun shutdown() {
        if (shuttingDown) return
        shuttingDown = true
        logger.info { "Shutting down session" }
        val jobs = synchronized(renderJobs) { renderJobs.values.toList() }
        runBlocking {
            jobs.forEach { it.cancelAndJoin() }
        }
        synchronized(renderJobs) { renderJobs.clear() }
        backend.eventLoop.close()
        backend.close()
        logger.info { "Session shut down" }
    }

    override fun close() = shutdown()
}
