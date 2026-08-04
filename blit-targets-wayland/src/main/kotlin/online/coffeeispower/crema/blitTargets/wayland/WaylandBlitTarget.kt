package online.coffeeispower.crema.blitTargets.wayland

import online.coffeeispower.crema.core.graphics.presentation.BlitTarget
import online.coffeeispower.crema.core.monitors.ConnectorManager
import online.coffeeispower.crema.core.platform.EventLoop

class WaylandBlitTarget : BlitTarget {
    override val connectorManager: ConnectorManager
        get() = TODO("Wayland outputs are not implemented yet")

    override val eventLoop: EventLoop
        get() = TODO("Wayland outputs are not implemented yet")

    override fun close() {
        // TODO: destroy the Wayland surface and shell objects
    }
}
