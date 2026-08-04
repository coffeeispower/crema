package online.coffeeispower.crema.core.graphics.renderer

import online.coffeeispower.crema.core.graphics.Color
import online.coffeeispower.crema.core.graphics.Rectangle
import online.coffeeispower.crema.core.graphics.RoundedRectangle
import online.coffeeispower.crema.core.graphics.gpu.GPU
import online.coffeeispower.crema.core.platform.linux.GPUScanoutBuffer

/**
 * The commands queued into a single frame recorded for a [buffer].
 *
 * A recording is handed to the [Renderer.beginFrame] block, which scopes its
 * lifetime: every command recorded here targets [buffer] on [gpu], and the
 * whole recording is submitted once the block returns. The renderer is
 * responsible for the Vulkan-level lifecycle (begin/end/submit) around it.
 */
interface FrameRecording {
    /** The scanout buffer this frame renders into. */
    val buffer: GPUScanoutBuffer

    /** The GPU the recorded work is submitted to. */
    val gpu: GPU

    /** Queues a fill of [buffer] with [color]. */
    fun clear(color: Color)

    fun drawRect(rect: Rectangle, fillColor: Color);
    fun drawRect(rect: RoundedRectangle, fillColor: Color);
    fun drawRectBorder(rect: RoundedRectangle, fillColor: Color);
}
