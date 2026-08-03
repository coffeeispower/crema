package online.coffeeispower.jayland.core

abstract class Renderer : AutoCloseable {
    abstract val deviceManager: DeviceManager

    /**
     * Renders one frame for [buffer]: begins a recording, runs [block] to queue
     * commands against it, then submits the recorded work and returns a
     * [Submission] that signals when the GPU has finished executing it.
     *
     * Implementations must guarantee that a throwing [block] leaves the renderer
     * reusable: any partially recorded work is discarded before the exception
     * propagates.
     */
    abstract fun beginFrame(buffer: GPUScanoutBuffer, block: FrameRecording.() -> Unit): Submission

    override fun close() {
        deviceManager.close()
    }
}
