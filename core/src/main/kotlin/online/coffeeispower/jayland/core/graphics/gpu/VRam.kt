package online.coffeeispower.jayland.core.graphics.gpu

import online.coffeeispower.jayland.core.graphics.ColorMode
import online.coffeeispower.jayland.core.platform.linux.GPUScanoutBuffer

interface VRam : AutoCloseable {
    /**
     * Allocates a scanout buffer of [width]x[height] in [colorMode].
     *
     * [allowedModifiers] lists the buffer layouts the destination output can
     * scan out, ordered by preference. The values are opaque to the core and
     * interpreted by the platform (DRM format modifiers on Linux): the output
     * declares what it accepts and the VRam must produce a buffer matching one
     * of them. When null/empty the VRam picks any layout it can scan out.
     */
    fun allocateBufferForScanout(
        width: Int,
        height: Int,
        colorMode: ColorMode = ColorMode.RGBA8,
        allowedModifiers: List<Long>? = null,
    ): GPUScanoutBuffer
}