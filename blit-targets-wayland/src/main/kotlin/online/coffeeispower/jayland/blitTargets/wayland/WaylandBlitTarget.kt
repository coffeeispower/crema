package online.coffeeispower.jayland.blitTargets.wayland

import online.coffeeispower.jayland.core.graphics.presentation.BlitTarget
import online.coffeeispower.jayland.core.monitors.ConnectorManager
import online.coffeeispower.jayland.core.platform.EventLoop

class WaylandBlitTarget : BlitTarget {
    override val connectorManager: ConnectorManager
        get() = TODO("Wayland outputs are not implemented yet")

    override val eventLoop: EventLoop
        get() = TODO("Wayland outputs are not implemented yet")

    override fun close() {
        // TODO: destroy the Wayland surface and shell objects
    }
}
