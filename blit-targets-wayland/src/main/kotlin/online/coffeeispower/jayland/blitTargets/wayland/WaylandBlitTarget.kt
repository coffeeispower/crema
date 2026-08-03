package online.coffeeispower.jayland.blitTargets.wayland

import online.coffeeispower.jayland.core.BlitTarget
import online.coffeeispower.jayland.core.ConnectorManager

class WaylandBlitTarget : BlitTarget {
    override val connectorManager: ConnectorManager
        get() = TODO("Wayland outputs are not implemented yet")

    override fun close() {
        // TODO: destroy the Wayland surface and shell objects
    }
}
