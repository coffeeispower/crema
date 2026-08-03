package online.coffeeispower.jayland.app

import kotlinx.coroutines.channels.Channel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import online.coffeeispower.jayland.core.Backend
import online.coffeeispower.jayland.core.BackendConfig
import online.coffeeispower.jayland.core.BlitTarget
import online.coffeeispower.jayland.core.ColorMode
import online.coffeeispower.jayland.core.Committer
import online.coffeeispower.jayland.core.Connector
import online.coffeeispower.jayland.core.ConnectorManager
import online.coffeeispower.jayland.core.DeviceManager
import online.coffeeispower.jayland.core.EventLoop
import online.coffeeispower.jayland.core.EventLoopEvent
import online.coffeeispower.jayland.core.Frame
import online.coffeeispower.jayland.core.FrameRecording
import online.coffeeispower.jayland.core.FrameResult
import online.coffeeispower.jayland.core.GPU
import online.coffeeispower.jayland.core.GPUScanoutBuffer
import online.coffeeispower.jayland.core.InputManager
import online.coffeeispower.jayland.core.Mode
import online.coffeeispower.jayland.core.Monitor
import online.coffeeispower.jayland.core.Output
import online.coffeeispower.jayland.core.PlatformBackend
import online.coffeeispower.jayland.core.Renderer
import online.coffeeispower.jayland.core.Signal
import online.coffeeispower.jayland.core.Submission
import online.coffeeispower.jayland.core.Swapchain
import online.coffeeispower.jayland.core.VRam
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine

class CompositorSessionTest {

    @Test
    fun startMonitorsRendersUntilShutdown() {
        val output = FakeOutput()
        val connector = FakeConnector(output)
        val loop = FakeEventLoop(listOf(connector))
        val backend = testBackend(loop, listOf(connector))
        val frames = AtomicInteger()

        val session = CompositorSession(backend) {
            frames.incrementAndGet()
            delay(5) // yield: a real frame suspends in acquireBuffer/commit
        }
        val runThread = Thread { session.run() }.apply { start() }

        awaitCondition({ frames.get() > 0 }) { "render loop never started" }
        assertTrue(frames.get() > 0)
        assertTrue(output.closeCount.get() == 0, "output closed while the render loop is still active")
        assertTrue(!loop.closed, "event loop closed before shutdown")

        session.shutdown()
        runThread.join(5_000)
        assertTrue(!runThread.isAlive, "session.run did not return after shutdown")
        assertTrue(loop.closed, "event loop was not closed")
        assertTrue(output.closeCount.get() >= 1, "output was not closed by the render loop")

        val framesAtShutdown = frames.get()
        Thread.sleep(100)
        assertEquals(framesAtShutdown, frames.get(), "render loop kept producing frames after shutdown")
    }

    @Test
    fun detachingAnOutputStopsItsRenderLoop() {
        val output = FakeOutput()
        val connector = FakeConnector(output)
        val loop = FakeEventLoop(listOf(connector))
        val backend = testBackend(loop, listOf(connector))
        val frames = AtomicInteger()

        val session = CompositorSession(backend) {
            frames.incrementAndGet()
            delay(5)
        }
        val runThread = Thread { session.run() }.apply { start() }

        awaitCondition({ frames.get() > 0 }) { "render loop never started" }

        // Simulates a monitor being unplugged: the output is detached and the
        // loop must observe it, stop, and release the output.
        output.detach()
        awaitCondition({ output.closeCount.get() >= 1 }) { "output was not closed after being detached" }
        val framesAtDetach = frames.get()
        Thread.sleep(100)
        assertEquals(framesAtDetach, frames.get(), "render loop kept producing frames after detach")

        session.shutdown()
        runThread.join(5_000)
        assertTrue(!runThread.isAlive, "session.run did not return after shutdown")
        assertTrue(loop.closed, "event loop was not closed")
    }

    @Test
    fun shutdownWithoutStartMonitorsIsHarmless() {
        val output = FakeOutput()
        val connector = FakeConnector(output)
        val loop = FakeEventLoop(listOf(connector))
        val backend = testBackend(loop, listOf(connector))

        val session = CompositorSession(backend) { }
        session.shutdown()
        assertTrue(loop.closed, "event loop was not closed")
        assertTrue(output.closeCount.get() == 0, "output was closed although no render loop ran")
    }

    @Test
    fun closingTheEventLoopReleasesARenderLoopAwaitingAPageFlip() {
        // The shutdown hook now only wakes the event loop on a signal. If a
        // render loop is suspended mid-commit (its page flip never arrives),
        // the loop must still release it so run() returns and the session's
        // finally tears the backend down, instead of runBlocking waiting on a
        // child coroutine that can never resume.
        val committer = BlockingFakeCommitter()
        val output = FakeOutput(committer)
        val connector = FakeConnector(output)
        val loop = FakeEventLoop(listOf(connector))
        val backend = testBackend(loop, listOf(connector))

        val session = CompositorSession(backend) {
            output.committer.commit(Frame(output.swapchain.acquireBuffer(), FakeSubmission()))
        }
        val runThread = Thread { session.run() }.apply { start() }

        awaitCondition({ committer.commitsStarted.get() > 0 }) { "render loop never reached commit" }

        loop.close()

        runThread.join(5_000)
        assertTrue(!runThread.isAlive, "session.run did not return after the loop was closed")
        assertTrue(loop.closed, "event loop was not closed")
        assertTrue(output.closeCount.get() >= 1, "output was not released by the render loop")
    }

    private fun testBackend(loop: FakeEventLoop, connectors: List<Connector>): Backend =
        Backend.create(
            BackendConfig(
                presentationBackend = "test",
                rendererBackend = "test",
                renderer = { FakeRenderer() },
                platform = { _ ->
                    PlatformBackend(
                        FakeBlitTarget(connectors, loop),
                        FakeInputManager(),
                        loop,
                    )
                },
            )
        )
}

// --- fakes ------------------------------------------------------------------

private class FakeRenderer : Renderer() {
    override val deviceManager = object : DeviceManager {
        override val gpus: List<GPU> = listOf(FakeGPU())
        override fun close() = Unit
    }

    override fun beginFrame(buffer: GPUScanoutBuffer, block: FrameRecording.() -> Unit): Submission =
        FakeSubmission()

    override fun close() = deviceManager.close()
}

private class FakeSubmission : Submission {
    override val gpu: GPU = FakeGPU()
    override val signal = object : Signal {
        override suspend fun awaitSignaled() = Unit
    }
    override fun exportInFenceFd(): Int = -1
    override fun close() = Unit
}

private class FakeGPU : GPU {
    override val name = "fake-gpu"
    override val vram = FakeVRam()
    override fun close() = Unit
}

private class FakeVRam : VRam {
    override fun allocateBufferForScanout(
        width: Int,
        height: Int,
        colorMode: ColorMode,
        allowedModifiers: List<Long>?,
    ): GPUScanoutBuffer = FakeBuffer()
    override fun close() = Unit
}

private class FakeBuffer : GPUScanoutBuffer {
    override val width = 0
    override val height = 0
    override val colorMode = ColorMode.RGBA8
    override val owner = FakeGPU()
    override fun close() = Unit
}

private class FakeConnector(private val output: FakeOutput) : Connector {
    override val enabled: Boolean = false
    override val preferredMode: Mode = output.mode
    override val monitor: Monitor = output.monitor
    override val gpu: GPU = FakeGPU()
    override fun enable(mode: Mode, vram: VRam): Output = output
    override fun close() = Unit
}

private class FakeOutput(private val committerOverride: Committer? = null) : Output {
    override var detached = false
        private set
    override val monitor = Monitor("test-monitor", 1920, 1080, 60)
    override val mode = Mode(1920, 1080, 60)
    override val swapchain: Swapchain = Swapchain(1920, 1080, ColorMode.RGBA8, vram = FakeVRam())
    override val committer: Committer = committerOverride ?: FakeCommitter()
    val closeCount = AtomicInteger(0)

    fun detach() {
        detached = true
    }

    override fun close() {
        closeCount.incrementAndGet()
    }
}

private class FakeCommitter : Committer {
    override suspend fun commit(frame: Frame): FrameResult =
        FrameResult(presented = true, presentedAt = 0L, frameSeq = 0L)
    override fun close() = Unit
}

/** A committer whose frames never present: the page flip never arrives. */
private class BlockingFakeCommitter : Committer {
    val commitsStarted = AtomicInteger(0)

    override suspend fun commit(frame: Frame): FrameResult {
        commitsStarted.incrementAndGet()
        return suspendCancellableCoroutine { }
    }

    override fun close() = Unit
}

private class FakeBlitTarget(
    private val connectors: List<Connector>,
    override val eventLoop: EventLoop,
) : BlitTarget {
    override val connectorManager = object : ConnectorManager {
        override val connectors = this@FakeBlitTarget.connectors
        override fun close() = Unit
    }
    override fun close() = Unit
}

private class FakeInputManager : InputManager

private class FakeEventLoop(private val connectors: List<Connector>) : EventLoop {
    private val closedSignal = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    var closed = false
        private set

    override fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit) {
        runBlocking {
            handler(EventLoopEvent.StartMonitors(connectors))
            closedSignal.receive()
            // Mirrors DRMEventLoop: the handler's render loops are children of
            // this runBlocking, which waits for them before returning.
            coroutineContext[Job]?.children?.forEach { it.cancelAndJoin() }
        }
    }

    override fun close() {
        closed = true
        closedSignal.trySend(Unit)
    }
}

private fun awaitCondition(condition: () -> Boolean, message: () -> String) {
    val deadline = System.nanoTime() + 5_000_000_000L
    while (!condition()) {
        if (System.nanoTime() > deadline) error(message())
        Thread.sleep(5)
    }
}
