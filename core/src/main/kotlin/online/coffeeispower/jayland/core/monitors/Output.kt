package online.coffeeispower.jayland.core.monitors

import online.coffeeispower.jayland.core.graphics.presentation.Committer
import online.coffeeispower.jayland.core.graphics.presentation.Swapchain

/**
 * An output that is ready to be used for drawing. This is created from a [Connector] using [Connector.enable].
 * Must be [closed][close] to release its swapchain, committer and CRTC resources.
 *
 * [online.coffeeispower.jayland.core.graphics.presentation.Committer.commit] suspends until the submitted frame's scanout has actually
 * completed (the page flip event has arrived), so the compositor can release the
 * frame's buffer back to [swapchain] as soon as [online.coffeeispower.jayland.core.graphics.presentation.Committer.commit] returns.
 */
interface Output : AutoCloseable {
    val detached: Boolean
    val monitor: Monitor
    val mode: Mode
    val swapchain: Swapchain
    val committer: Committer

    override fun close()
}
