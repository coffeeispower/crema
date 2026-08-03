package online.coffeeispower.jayland.core

/**
 * A physical graphical port from which a monitor can be connected to, like an HDMI, Display Port or VGA port.
 * Must be [closed][close] when the port is unplugged or no longer used.
 */
interface Connector : AutoCloseable {
    val enabled: Boolean
    val preferredMode: Mode
    val monitor: Monitor
    val gpu: GPU

    fun enable(mode: Mode, vram: VRam): Output
}

/**
 * An output that is ready to be used for drawing. This is created from a [Connector] using [Connector.enable].
 * Must be [closed][close] to release its swapchain, committer and CRTC resources.
 *
 * [Committer.commit] suspends until the submitted frame's scanout has actually
 * completed (the page flip event has arrived), so the compositor can release the
 * frame's buffer back to [swapchain] as soon as [Committer.commit] returns.
 */
interface Output : AutoCloseable {
    val detached: Boolean
    val monitor: Monitor
    val mode: Mode
    val swapchain: Swapchain
    val committer: Committer

    override fun close()
}


/** Physical specs of the monitor */
data class Monitor(
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRateHz: Int,
    val supportedColorModes: List<ColorMode> = listOf(),
);
/**
 * Mode of an output or connector
 */
data class Mode(val width: Int, val height: Int, val refreshRateHz: Int)
