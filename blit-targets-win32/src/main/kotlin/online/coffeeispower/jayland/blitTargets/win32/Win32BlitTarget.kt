package online.coffeeispower.jayland.blitTargets.win32

import online.coffeeispower.jayland.core.BlitTarget
import online.coffeeispower.jayland.core.ConnectorManager

class Win32BlitTarget : BlitTarget {
    override val connectorManager: ConnectorManager
        get() = TODO("Windows monitors are not implemented yet")

    override fun close() {
        // TODO: destroy the window and its swapchain
    }
}
