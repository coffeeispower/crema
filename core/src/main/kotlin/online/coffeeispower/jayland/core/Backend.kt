package online.coffeeispower.jayland.core

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.utils.errors.UnsupportedPlatformException

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

    override fun close() {
        logger.info { "Shutting down backend" }
        eventLoop.close()
        inputManager.close()
        blitTarget.close()
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
                } catch (e: Exception) {
                    logger.error(e) { "Backend '${config.name}' failed unexpectedly, trying the next one" }
                }
            }
            val tried = availableBackends.joinToString(", ") { "'${it.name}'" }
            throw UnsupportedPlatformException(reason = "no usable backend was found (tried: $tried)")
        }
    }
}
