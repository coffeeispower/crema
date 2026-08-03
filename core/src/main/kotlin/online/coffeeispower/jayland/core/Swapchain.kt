package online.coffeeispower.jayland.core

import kotlinx.coroutines.sync.Semaphore

/**
 * A rotating pool of [GPUScanoutBuffer]s used for presentation. [acquireBuffer]
 * hands out a free buffer and suspends while every buffer in the pool is still
 * in flight, so the pool depth is the maximum number of frames that can be
 * presented concurrently.
 *
 * Buffers are allocated eagerly from [vram] and returned to the pool with
 * [release] once their scanout has completed. The pool owns the buffers and
 * destroys them when this swapchain is [closed][close].
 */
class Swapchain(
    val width: Int,
    val height: Int,
    val colorMode: ColorMode,
    val depth: Int = 2,
    private val vram: VRam,
) : AutoCloseable {

    private val buffers = List(depth) { vram.allocateBufferForScanout(width, height, colorMode) }
    private val free = ArrayDeque<GPUScanoutBuffer>().apply { addAll(buffers) }
    private val freeSlots = Semaphore(depth)
    private val lock = Any()
    private var closed = false

    init {
        require(depth >= 1) { "Swapchain depth must be at least 1" }
    }

    /** Returns the next free buffer, suspending until one is available. */
    suspend fun acquireBuffer(): GPUScanoutBuffer {
        freeSlots.acquire()
        synchronized(lock) {
            return free.removeFirst()
        }
    }

    /** Returns [buffer] to the pool once its scanout has completed. */
    fun release(buffer: GPUScanoutBuffer) {
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
