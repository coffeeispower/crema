package online.coffeeispower.jayland.blitTargets.drm

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.BlitTarget
import online.coffeeispower.jayland.core.Connector
import online.coffeeispower.jayland.core.ConnectorManager
import online.coffeeispower.jayland.core.GPU
import online.coffeeispower.jayland.core.platform.linux.DrmGPU
import online.coffeeispower.jayland.utils.errors.UnsupportedPlatformException

/**
 * A [BlitTarget] that presents frames through DRM/KMS on every card driven by
 * the given [gpus]. For each GPU that can drive a card (a [DrmGPU] with a
 * `drmProps.primary` dev_t), the card path is resolved via [CardPathResolver]
 * and opened; non-DRM GPUs (e.g. software renderers) are skipped with a log.
 * When a card cannot be opened (missing privileges / no DRM node), or no GPU
 * can drive a card at all, construction of the [connectorManager] fails with
 * [UnsupportedPlatformException] so the backend can fall back to another
 * presentation backend. Every connector is bound to the GPU of its own card.
 */
class DRMBlitTarget(private val gpus: List<GPU>) : BlitTarget {

    constructor(gpu: GPU) : this(listOf(gpu))

    private val logger = KotlinLogging.logger {}

    private var devices: List<DRMDevice> = emptyList()
    private var eventLoop: DRMEventLoop? = null

    private val connectorManagerDelegate = lazy { createConnectorManager() }
    override val connectorManager: ConnectorManager by connectorManagerDelegate

    private fun createConnectorManager(): ConnectorManager {
        val opened = mutableListOf<Pair<DRMDevice, GPU>>()
        try {
            for (gpu in gpus) {
                val drmGpu = gpu as? DrmGPU
                if (drmGpu == null) {
                    logger.warn { "GPU '${gpu.name}' cannot drive a DRM card (not a DrmGPU), skipping it" }
                    continue
                }
                val primary = drmGpu.drmProps.primary
                if (primary == null) {
                    logger.warn { "GPU '${gpu.name}' has no DRM primary node, skipping it" }
                    continue
                }
                val (major, minor) = primary
                val cardPath = CardPathResolver.resolve(major.toInt(), minor.toInt())
                    ?: throw UnsupportedPlatformException(reason = "no /dev/dri/cardN for GPU '${gpu.name}' ($major:$minor)")
                val dev = try {
                    DRMDevice.open(cardPath)
                } catch (e: Exception) {
                    throw UnsupportedPlatformException(cause=e, feature = "drm", reason = "device open failed for GPU '${gpu.name}' ($major:$minor | $cardPath)")
                }
                opened += dev to gpu
                logger.info { "Opened DRM card ${cardPath.fileName} for ${gpu.name}" }
            }
            if (opened.isEmpty()) {
                throw UnsupportedPlatformException(reason = "no GPU available to drive a DRM card")
            }
            devices = opened.map { it.first }
            // The event loop's hotplug callback needs the connector manager,
            // and the manager needs the loop (for page flips). Wire them up
            // through a deferred local instead of re-entering the lazy that is
            // currently initializing (which would recurse forever).
            lateinit var composite: ConnectorManager
            val loop = DRMEventLoop(devices) { composite.connectors }
            eventLoop = loop
            val managers = opened.map { (dev, gpu) -> DRMConnectorManager(dev, gpu, loop) }
            logger.info { "DRM blit target driving ${devices.size} card(s)" }
            composite = object : ConnectorManager {
                // Eager: a card with no usable connector fails backend creation
                // here instead of silently presenting nothing.
                override val connectors: List<Connector> = managers.flatMap { it.connectors }
                override fun close() = managers.forEach { it.close() }
            }
            return composite
        } catch (t: Throwable) {
            // Never leak already-opened cards when a later one fails to open.
            eventLoop?.close()
            eventLoop = null
            opened.forEach { it.first.close() }
            devices = emptyList()
            throw t
        }
    }

    override fun close() {
        // Only close what actually came up. If connectorManager initialization
        // failed it already released everything, and re-running the lazy here
        // would reopen the cards just to fail again.
        if (connectorManagerDelegate.isInitialized()) connectorManager.close()
        eventLoop?.close()
        devices.forEach { it.close() }
    }
}
