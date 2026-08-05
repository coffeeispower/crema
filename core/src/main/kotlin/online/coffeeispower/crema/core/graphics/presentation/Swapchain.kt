package online.coffeeispower.crema.core.graphics.presentation

import kotlinx.coroutines.sync.Semaphore
import online.coffeeispower.crema.core.graphics.ColorMode
import online.coffeeispower.crema.core.platform.linux.GPUScanoutImageBuffer
import online.coffeeispower.crema.core.graphics.gpu.VRam

/**
 * A rotating pool of [GPUScanoutImageBuffer]s used for presentation. [acquireBuffer]
 * hands out a free buffer and suspends while every buffer in the pool is still
 * in flight, so the pool depth is the maximum number of frames that can be
 * presented concurrently.
 *
 * Buffers are allocated eagerly from [vram] and returned to the pool with
 * [release] once their scanout has completed. The pool owns the buffers and
 * destroys them when this swapchain is [closed][close].
 *
 * The output drives the buffer layout: [allowedModifiers] are the layouts the
 * output can scan out (opaque DRM format modifiers on Linux), and every buffer
 * is created with one of them instead of the VRam guessing what the output
 * accepts.
 */
class Swapchain(
    val width: Int,
    val height: Int,
    val colorMode: ColorMode,
    val depth: Int = 2,
    private val vram: VRam,
    allowedModifiers: List<Long>? = null,
) : AutoCloseable {

    // Validated before any allocation: init blocks run in declaration order, so
    // a bad depth fails the constructor before a single buffer is created (and
    // before the pool could deadlock on a zero-permit semaphore).
    init {
        require(depth >= 1) { "Swapchain depth must be at least 1" }
    }

    private val buffers = List(depth) { vram.allocateBufferForScanout(width, height, colorMode, allowedModifiers) }
    private val free = ArrayDeque<GPUScanoutImageBuffer>().apply { addAll(buffers) }
    private val freeSlots = Semaphore(depth)
    private val lock = Any()
    private var closed = false

    /** Returns the next free buffer, suspending until one is available. */
    suspend fun acquireBuffer(): GPUScanoutImageBuffer {
        freeSlots.acquire()
        synchronized(lock) {
            return free.removeFirst()
        }
    }

    /** Returns [buffer] to the pool once its scanout has completed. */
    fun release(buffer: GPUScanoutImageBuffer) {
        check(buffer in buffers) { "Cannot release a buffer this swapchain does not own" }
        synchronized(lock) {
            free.addLast(buffer)
        }
        freeSlots.release()
    }

    override fun close() {
        if (closed) return
        closed = true
        buffers.forEach { it.close() }
    }
}
