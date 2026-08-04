package online.coffeeispower.jayland.renderers.vulkan

import online.coffeeispower.jayland.core.synchronization.Latch
import online.coffeeispower.jayland.core.graphics.gpu.Submission
import online.coffeeispower.jayland.lwjgl.memStack
import online.coffeeispower.jayland.lwjgl.outInt
import online.coffeeispower.jayland.utils.fds.PollDispatcher
import online.coffeeispower.jayland.utils.fds.Posix
import org.lwjgl.vulkan.KHRExternalSemaphoreFd
import org.lwjgl.vulkan.VK10.vkDestroySemaphore
import org.lwjgl.vulkan.VK11
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.VkSemaphoreGetFdInfoKHR

/**
 * A [Submission] backed by a binary semaphore that is exported (exactly once)
 * as a `SYNC_FD` on first use. The exported fd can be handed to a presentation
 * backend with [exportInFenceFd] (ownership transfers to the caller), while a
 * duplicated fd keeps [latch] awaitable in software — the kernel waiting on
 * the in-fence and a software waiter can never race on the same descriptor.
 */
class VulkanSubmission(
    override val gpu: VulkanGPU,
    private val device: VkDevice,
    private val semaphore: Long,
    private val dispatcher: PollDispatcher,
) : Submission {

    private val exportLock = Any()
    private var exported = false
    private var inFenceFd = -1
    private var waitFd = -1
    private var closed = false

    override val latch: Latch by lazy {
        object : Latch {
            override suspend fun await() {
                val fd = dupWaitFd()
                dispatcher.watch(fd).use { poller ->
                    poller.awaitReadable()
                    Posix.drain(fd)
                }
            }
        }
    }

    override fun exportInFenceFd(): Int {
        ensureExported()
        val fd = inFenceFd
        check(fd != -1) { "In-fence fd already handed to a committer" }
        inFenceFd = -1
        return fd
    }

    private fun dupWaitFd(): Int {
        ensureExported()
        return Posix.dup(waitFd)
    }

    private fun ensureExported() {
        if (exported) return
        synchronized(exportLock) {
            if (exported) return
            val fd = memStack {
                outInt { buf ->
                    KHRExternalSemaphoreFd.vkGetSemaphoreFdKHR(
                        device,
                        VkSemaphoreGetFdInfoKHR.calloc(this)
                            .`sType$Default`()
                            .semaphore(semaphore)
                            .handleType(VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT),
                        buf,
                    ).checkAsVkError("export sync-fd")
                }
            }
            inFenceFd = fd
            waitFd = Posix.dup(fd)
            Posix.setNonBlocking(waitFd)
            exported = true
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        if (inFenceFd != -1) Posix.close(inFenceFd)
        if (waitFd != -1) Posix.close(waitFd)
        vkDestroySemaphore(device, semaphore, null)
    }
}
