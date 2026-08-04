package online.coffeeispower.jayland.core.graphics.gpu

import online.coffeeispower.jayland.core.synchronization.Latch

/**
 * A render submission: the work a [online.coffeeispower.jayland.core.graphics.renderer.Renderer] recorded for the GPU that owns it,
 * plus the sync primitives that mark its completion.
 *
 * [exportInFenceFd] hands the GPU-completion signal to a presentation backend
 * as an opaque in-fence (e.g. KMS `IN_FENCE_FD`), transferring ownership of the
 * returned file descriptor to the caller. The software-side [latch] remains
 * awaitable independently (it dup()s the underlying sync object), so a frame can
 * be waited on in-process or handed to the kernel without the two ever racing
 * on the same fd.
 *
 * Must be [closed][close] once the frame it belongs to is no longer in flight.
 */
interface Submission : AutoCloseable {
    /** The GPU the submitted work runs on. */
    val gpu: GPU

    /** Opaque software-side completion latch. */
    val latch: Latch

    /**
     * Exports the completion signal as a pollable in-fence file descriptor,
     * transferring its ownership to the caller. May only be called once.
     */
    fun exportInFenceFd(): Int
}