package online.coffeeispower.crema.core.platform

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.core.input.InputManager
import online.coffeeispower.crema.core.graphics.renderer.Renderer
import online.coffeeispower.crema.core.graphics.gpu.DeviceManager
import online.coffeeispower.crema.core.graphics.presentation.BlitTarget
import online.coffeeispower.crema.core.monitors.Connector
import online.coffeeispower.crema.core.monitors.Mode
import online.coffeeispower.crema.core.monitors.Output
import online.coffeeispower.crema.utils.errors.UnsupportedPlatformException

/**
 * Platform-agnostic composition of a [Renderer] and a [PlatformBackend].
 * Knows nothing about vulkan or drm: it only wires the renderer's GPU devices
 * into the platform's connectors and delegates the event loop and input to the
 * platform bundle.
 */
class Backend private constructor(
    val renderer: Renderer,
    val platform: PlatformBackend,
) : AutoCloseable {
    val deviceManager: DeviceManager
        get() = renderer.deviceManager
    val blitTarget: BlitTarget
        get() = platform.blitTarget
    val inputManager: InputManager
        get() = platform.inputManager
    val eventLoop: EventLoop
        get() = platform.eventLoop
    val connectors: List<Connector>
        get() = platform.blitTarget.connectorManager.connectors

    /**
     * Enables [connector] at [mode] (defaults to its preferred mode), backing
     * its swapchain with buffers from the GPU that drives it.
     */
    fun enable(connector: Connector, mode: Mode = connector.preferredMode): Output =
        connector.enable(mode, connector.gpu.vram).also {
            logger.info { "Enabled connector ${connector.monitor.name} at ${mode.width}x${mode.height}@${mode.refreshRateHz}Hz" }
        }

    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        logger.info { "Shutting down backend" }
        // Shut the reactor down first so no page flips can arrive while the
        // committers and outputs below are torn down.
        eventLoop.close()
        blitTarget.close()
        inputManager.close()
        renderer.close()
        logger.info { "Backend shut down" }
    }

    companion object {
        @JvmStatic
        private val logger = KotlinLogging.logger {}

        @JvmStatic
        fun create(config: BackendConfig): Backend {
            logger.info { "Creating backend '${config.name}'" }
            logger.info { "Creating renderer for '${config.name}'" }
            val renderer = try {
                config.renderer()
            } catch (e: Throwable) {
                logger.error(e) { "Backend '${config.name}' renderer creation failed" }
                throw e
            }
            logger.info { "Renderer created for '${config.name}'" }
            logger.info { "Creating platform for '${config.name}'" }
            val platform = try {
                config.platform(renderer.deviceManager)
            } catch (e: Throwable) {
                logger.error(e) { "Backend '${config.name}' platform creation failed, releasing renderer" }
                renderer.close()
                throw e
            }
            logger.info { "Platform created for '${config.name}'" }
            val backend = Backend(renderer, platform)
            try {
                val connectors = backend.connectors;
                logger.info { "Backend '${config.name}' created with ${connectors.size} connector(s)" }
            } catch (e: Throwable) {
                backend.close()
                throw e
            }
            return backend
        }

        fun chooseBackend(availableBackends: Array<BackendConfig>): Backend {
            for (config in availableBackends) {
                try {
                    return create(config).also {
                        logger.info { "Using backend '${config.name}'" }
                    }
                } catch (e: NotImplementedError) {
                    logger.warn(e) { "Backend '${config.name}' is not fully implemented yet, skipping" }
                } catch (e: UnsupportedPlatformException) {
                    logger.info(e) { "Backend '${config.name}' is not supported, trying the next one" }
                }
                // Any other exception is a bug in the backend under test, not an
                // unsupported platform: let it crash loudly instead of silently
                // falling through to a backend that may render nothing at all.
            }
            val tried = availableBackends.joinToString(", ") { "'${it.name}'" }
            throw UnsupportedPlatformException(reason = "no usable backend was found (tried: $tried)")
        }
    }
}
