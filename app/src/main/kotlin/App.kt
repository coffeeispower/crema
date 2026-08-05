package online.coffeeispower.crema.app
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import online.coffeeispower.crema.renderers.vulkan.*
import online.coffeeispower.crema.blitTargets.drm.*
import online.coffeeispower.crema.blitTargets.win32.*
import online.coffeeispower.crema.blitTargets.wayland.*
import online.coffeeispower.crema.utils.errors.*
import online.coffeeispower.crema.utils.logging.LogArchiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import online.coffeeispower.crema.core.graphics.Border
import online.coffeeispower.crema.core.graphics.Color
import online.coffeeispower.crema.core.graphics.Rectangle
import online.coffeeispower.crema.core.graphics.presentation.Frame
import online.coffeeispower.crema.core.monitors.Output
import online.coffeeispower.crema.core.graphics.renderer.Renderer
import online.coffeeispower.crema.core.input.InputManager
import online.coffeeispower.crema.core.platform.Backend
import online.coffeeispower.crema.core.platform.BackendConfig
import online.coffeeispower.crema.core.platform.EventLoop
import online.coffeeispower.crema.core.platform.EventLoopEvent
import online.coffeeispower.crema.core.platform.PlatformBackend

fun main() {

    // Minecraft-style session logs: gzip the previous session's log into a
    // numbered archive and start a fresh file at the normal path, so
    // crema.log only ever holds the current session. Must run before any
    // logging happens (log4j2 would otherwise (re)create the file).
    LogArchiver.archivePreviousSession()

    // Anything that escapes the event loop surfaces here: print the panic and a
    // crash report to the TTY, and append the same to the session log file so it
    // is complete when reporting the crash. (The log path itself lives in
    // CrashReport.logFile log4j2 resolves it via CremaLogFileLookup.)
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        val panic = Panic(throwable)
        val err = TeePrintStream(System.err, CrashReport.logFile)
        panic.printStackTrace(err)
        CrashReport.print(err, panic)
        err.flush()
    }

    KotlinLoggingConfiguration.logStartupMessage = false
    val availableBackends = arrayOf(
        BackendConfig(
            presentationBackend = "drm",
            inputBackend = "libinput",
            rendererBackend = "vulkan",
            renderer = { VulkanRenderer() },
            platform = { deviceManager ->
                val blit = DRMBlitTarget(deviceManager.gpus)
                PlatformBackend(blit, object : InputManager {}, blit.eventLoop)
            },
        ),
        BackendConfig(
            presentationBackend = "wayland",
            rendererBackend = "vulkan",
            renderer = { VulkanRenderer() },
            platform = { _ -> PlatformBackend(WaylandBlitTarget(), object : InputManager {}, stubEventLoop()) },
        ),
        BackendConfig(
            presentationBackend = "win32",
            rendererBackend = "vulkan",
            renderer = { VulkanRenderer() },
            platform = { _ -> PlatformBackend(Win32BlitTarget(), object : InputManager {}, stubEventLoop()) },
        ),
    )

    KotlinLogging.logger{}.info{
        "\r"+"""
                               Welcome to
                                                                              
      _____        _____        ______        ______  _______         _____   
  ___|\    \   ___|\    \   ___|\     \      |      \/       \    ___|\    \  
 /    /\    \ |    |\    \ |     \     \    /          /\     \  /    /\    \ 
|    |  |    ||    | |    ||     ,_____/|  /     /\   / /\     ||    |  |    |
|    |  |____||    |/____/ |     \--'\_|/ /     /\ \_/ / /    /||    |__|    |
|    |   ____ |    |\    \ |     /___/|  |     |  \|_|/ /    / ||    .--.    |
|    |  |    ||    | |    ||     \____|\ |     |       |    |  ||    |  |    |
|\ ___\/    /||____| |____||____ '     /||\____\       |____|  /|____|  |____|
| |   /____/ ||    | |    ||    /_____/ || |    |      |    | / |    |  |    |
 \|___|    | /|____| |____||____|     | / \|____|      |____|/  |____|  |____|
   \( |____|/   \(     )/    \( |_____|/     \(          )/       \(      )/  
    '   )/       '     '      '    )/         '          '         '      '   


                 The extensible JVM-based Wayland compositor!

"""}
    // chooseBackend will choose the best suiting backend for the current environment and operating system
    val backend = try {
        Backend.chooseBackend(availableBackends)
    } catch (e: UnsupportedPlatformException) {
        throw Panic(e)
    }
    val renderer = backend.renderer

    // Block until shutdown: the session runs the platform event loop (on the
    // reactor thread), owns the per-output render loops, and tears everything
    // down on Ctrl+C / panic.
    CompositorSession(backend) { output ->
        renderFrame(
            renderer,
            output
        )
    }.run()
}
data object TestRectangle {
    val animationStartNanos = System.nanoTime()
    val rectangle = Rectangle(100f, 100f, 1920f*0.2f, 1200f*0.2f)
    val roundedRectangle = rectangle.toRounded(40f)
    val fillColor = Color.WHITE
    val border = Border(Color.GREEN, 3f)
}
private val appLogger = KotlinLogging.logger {}

/**
 * Produces one frame for [output]: acquire a buffer from its swapchain, record
 * and dispatch the frame's commands into it, then commit it to the screen. The
 * commit runs in a child coroutine — [online.coffeeispower.crema.core.graphics.presentation.Committer.commit] suspends until the page flip
 * completes, and the render loop must not wait for that — so the CPU keeps
 * recording the next frame while the GPU renders this one and the kernel scans
 * out the previous one. The committer owns the frame's submission from
 * [online.coffeeispower.crema.core.graphics.presentation.Frame] on and closes it once the kernel is done with it.
 * A failed commit is logged and skipped: the committer has already returned the
 * frame's buffer to the swapchain, so the loop simply keeps going.
 */
private suspend fun CoroutineScope.renderFrame(renderer: Renderer, output: Output) {
    val buffer = output.swapchain.acquireBuffer()
    val submission = renderer.beginFrame(buffer) {
        clear(Color.BLACK)
        // Oscillate the test rectangle so we can see the output is live.
        val t = (System.nanoTime() - TestRectangle.animationStartNanos) / 1e9
        val base = TestRectangle.rectangle
        val maxX = (output.logicalSize.width - base.width).toFloat()
        val maxY = (output.logicalSize.height - base.height).toFloat()
        val rect = base.copy(
            x = maxX * 0.5f * (1f + kotlin.math.sin(t.toFloat())),
            y = maxY * 0.5f * (1f + kotlin.math.cos(t.toFloat())),
            width = base.width * (1f + 0.2f * kotlin.math.sin(2f * t.toFloat())),
            height = base.height * (1f + 0.2f * kotlin.math.cos(2f * t.toFloat())),
        )
        drawRect(
            rect = rect.toRounded(40f),
            fillColor = TestRectangle.fillColor,
            border = TestRectangle.border,
        )
    }

    launch {
        try {
            output.committer.commit(Frame(buffer, submission))
        } catch (e: Throwable) {
            appLogger.warn(e) { "frame commit failed, skipping" }
        }
    }
}

private fun stubEventLoop() = object : EventLoop {
    override fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit) {
        TODO("the chosen platform's event loop is not implemented yet")
    }

    override fun close() = Unit
}
