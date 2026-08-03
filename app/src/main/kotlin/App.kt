package online.coffeeispower.jayland.app

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import online.coffeeispower.jayland.core.*;
import online.coffeeispower.jayland.renderers.vulkan.*;
import online.coffeeispower.jayland.blitTargets.drm.*;
import online.coffeeispower.jayland.blitTargets.win32.*;
import online.coffeeispower.jayland.blitTargets.wayland.*;
import online.coffeeispower.jayland.utils.errors.*
import online.coffeeispower.jayland.utils.logging.LogArchiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

fun main() {
    // Minecraft-style session logs: gzip the previous session's log into a
    // numbered archive and start a fresh file at the normal path, so
    // jayland.log only ever holds the current session. Must run before any
    // logging happens (log4j2 would otherwise (re)create the file).
    LogArchiver.archivePreviousSession();

    // Anything that escapes the event loop surfaces here: print the panic and a
    // crash report to the TTY, and append the same to the session log file so it
    // is complete when reporting the crash. (The log path itself lives in
    // CrashReport.logFile; log4j2 resolves it via JaylandLogFileLookup.)
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        val panic = Panic(throwable);
        val out = TeePrintStream(System.err, CrashReport.logFile);
        panic.printStackTrace(out);
        CrashReport.print(out, panic);
        out.flush();
    };

    KotlinLoggingConfiguration.logStartupMessage = false;
    val availableBackends = arrayOf(
        BackendConfig(
            presentationBackend = "drm",
            inputBackend = "libinput",
            rendererBackend = "vulkan",
            renderer = { VulkanRenderer() },
            platform = { deviceManager ->
                // DRMBlitTarget opens every GPU's card and presents on all their
                // connectors; throws UnsupportedPlatformException when none qualify.
                PlatformBackend(DRMBlitTarget(deviceManager.gpus), object : InputManager {}, stubEventLoop())
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
    println()
    println(
        """
                               Welcome to
         ___  _______  __   __  ___      _______  __    _  ______  
        |   ||   _   ||  | |  ||   |    |   _   ||  |  | ||      | 
        |   ||  |_|  ||  |_|  ||   |    |  |_|  ||   |_| ||  _    |
        |   ||       ||       ||   |    |       ||       || | |   |
     ___|   ||       ||_     _||   |___ |       ||  _    || |_|   |
    |       ||   _   |  |   |  |       ||   _   || | |   ||       |
    |_______||__| |__|  |___|  |_______||__| |__||_|  |__||______| 


              The extensible JVM-based Wayland compositor!

""")
    // chooseBackend will choose the best suiting backend for the current environment and operating system
    val backend = try {
        Backend.chooseBackend(availableBackends)
    } catch (e: UnsupportedPlatformException) {
        throw Panic(e);
    };
    val renderer = backend.renderer;

    // block until shutdown; runs on the poll-dispatcher thread (epoll/kqueue/WAIT)
    val started = mutableSetOf<Connector>();
    backend.eventLoop.run { event ->
        when (event) {
            is EventLoopEvent.StartMonitors -> event.connectors.forEach { connector ->
                if (started.add(connector)) {
                    launch {
                        val output = backend.enable(connector);
                        while (!output.detached) {
                            val buffer = output.swapchain.acquireBuffer();   // N-deep pool, suspends when full
                            val submission = renderer.beginFrame(buffer) {   // record + dispatch the frame's commands
                                clear(Color.RED);                            // DSL: queue a fill of `buffer`
                            };
                            val result = output.committer.commit(Frame(buffer, submission)); // suspends until the page flip completes
                            submission.close();                              // KMS is done with the in-fence
                            // result.presentedAt / result.presented / result.frameSeq
                        }
                        started.remove(connector);
                        output.close();                                 // release CRTC/encoder/swapchain
                    }
                }
            }
            is EventLoopEvent.MonitorConnected -> Unit
            is EventLoopEvent.MonitorDisconnected -> Unit
        }
    }
}

private fun stubEventLoop() = object : EventLoop {
    override fun run(handler: suspend CoroutineScope.(EventLoopEvent) -> Unit) {
        TODO("the chosen platform's event loop is not implemented yet")
    }

    override fun close() = Unit
}
