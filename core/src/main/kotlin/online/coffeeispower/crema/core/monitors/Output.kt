package online.coffeeispower.crema.core.monitors

import online.coffeeispower.crema.core.graphics.presentation.Committer
import online.coffeeispower.crema.core.graphics.presentation.Swapchain
import online.coffeeispower.crema.core.units.LogicalSize
import online.coffeeispower.crema.core.units.PhysicalSize
import online.coffeeispower.crema.core.units.ScaleFactor
import online.coffeeispower.crema.core.units.toLogical

/**
 * An output that is ready to be used for drawing. This is created from a [Connector] using [Connector.enable].
 * Must be [closed][close] to release its swapchain, committer and CRTC resources.
 *
 * [online.coffeeispower.crema.core.graphics.presentation.Committer.commit] suspends until the submitted frame's scanout has actually
 * completed (the page flip event has arrived), so the compositor can release the
 * frame's buffer back to [swapchain] as soon as [online.coffeeispower.crema.core.graphics.presentation.Committer.commit] returns.
 */
interface Output : AutoCloseable {
    val detached: Boolean
    val monitor: Monitor
    val mode: Mode
    val swapchain: Swapchain
    val committer: Committer

    /**
     * The scale factor that relates this output's [physicalSize] to its
     * [logicalSize]. Fractional values are supported.
     */
    val scaleFactor: ScaleFactor

    /** The output's size in physical pixels, taken from [mode]. */
    val physicalSize: PhysicalSize<Double>
        get() = PhysicalSize(mode.width.toDouble(), mode.height.toDouble())

    /** The output's size in logical pixels: [physicalSize] divided by [scaleFactor]. */
    val logicalSize: LogicalSize<Double>
        get() = physicalSize.toLogical(scaleFactor)

    override fun close()
}
