package online.coffeeispower.crema.app;
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import online.coffeeispower.crema.renderers.vulkan.*;
import online.coffeeispower.crema.blitTargets.drm.*;
import online.coffeeispower.crema.blitTargets.win32.*;
import online.coffeeispower.crema.blitTargets.wayland.*;
import online.coffeeispower.crema.utils.errors.*
import online.coffeeispower.crema.utils.logging.LogArchiver
import kotlinx.coroutines.CoroutineScope
import online.coffeeispower.crema.core.graphics.Color
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
    LogArchiver.archivePreviousSession();

    // Anything that escapes the event loop surfaces here: print the panic and a
    // crash report to the TTY, and append the same to the session log file so it
    // is complete when reporting the crash. (The log path itself lives in
    // CrashReport.logFile; log4j2 resolves it via CremaLogFileLookup.)
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        val panic = Panic(throwable);
        val err = TeePrintStream(System.err, CrashReport.logFile);
        panic.printStackTrace(err);
        CrashReport.print(err, panic);
        err.flush();
    };

    KotlinLoggingConfiguration.logStartupMessage = false;
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
    );

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
        throw Panic(e);
    };
    val renderer = backend.renderer;

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

/**
 * Produces one frame for [output]: acquire a buffer from its swapchain, record
 * and dispatch the frame's commands into it, then commit it to the screen.
 * [online.coffeeispower.crema.core.graphics.presentation.Committer.commit] suspends until the page flip completes; [Submission.close] is always
 * run (even on cancellation) so the in-fence is released exactly once.
 */
private suspend fun CoroutineScope.renderFrame(renderer: Renderer, output: Output) {
    val buffer = output.swapchain.acquireBuffer();
    val submission = renderer.beginFrame(buffer) {
        clear(Color.RED);
    };
    try {
        output.committer.commit(Frame(buffer, submission));
    } finally {
        submission.close();
    }
}

private fun stubEventLoop() = object : EventLoop {
    override fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit) {
        TODO("the chosen platform's event loop is not implemented yet")
    }

    override fun close() = Unit
}
