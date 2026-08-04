package online.coffeeispower.jayland.core.monitors

import online.coffeeispower.jayland.core.graphics.gpu.GPU
import online.coffeeispower.jayland.core.graphics.gpu.VRam

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