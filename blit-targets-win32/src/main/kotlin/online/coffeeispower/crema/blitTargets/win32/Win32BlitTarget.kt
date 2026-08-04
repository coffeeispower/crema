package online.coffeeispower.crema.blitTargets.win32

import online.coffeeispower.crema.core.graphics.presentation.BlitTarget
import online.coffeeispower.crema.core.monitors.ConnectorManager
import online.coffeeispower.crema.core.platform.EventLoop

class Win32BlitTarget : BlitTarget {
    override val connectorManager: ConnectorManager
        get() = TODO("Windows monitors are not implemented yet")

    override val eventLoop: EventLoop
        get() = TODO("Windows monitors are not implemented yet")

    override fun close() {
        // TODO: destroy the window and its swapchain
    }
}
