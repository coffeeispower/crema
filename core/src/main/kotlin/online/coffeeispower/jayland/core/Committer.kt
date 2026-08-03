package online.coffeeispower.jayland.core

/**
 * Presents frames produced by the compositor to a [BlitTarget]. [commit]
 * suspends until the frame has been scanned out; implementations are expected
 * to return the frame's [Frame.buffer] to the [Swapchain] it came from once
 * the scanout has completed, so the buffer can be reused.
 */
interface Committer : AutoCloseable {
    suspend fun commit(frame: Frame): FrameResult
}
