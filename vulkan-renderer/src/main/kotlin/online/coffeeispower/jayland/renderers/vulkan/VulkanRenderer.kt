package online.coffeeispower.jayland.renderers.vulkan

import online.coffeeispower.jayland.core.FrameRecording
import online.coffeeispower.jayland.core.GPUScanoutBuffer
import online.coffeeispower.jayland.core.Renderer
import online.coffeeispower.jayland.core.Submission
import online.coffeeispower.jayland.lwjgl.memStack
import online.coffeeispower.jayland.lwjgl.outLong
import online.coffeeispower.jayland.lwjgl.outPointer
import online.coffeeispower.jayland.utils.errors.UnsupportedPlatformException
import online.coffeeispower.jayland.utils.fds.PollDispatcher
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT
import org.lwjgl.vulkan.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Renderer] that records each frame as transfer commands and submits them to
 * a graphics queue, signalling completion through an exportable binary
 * semaphore per submission.
 *
 * [beginFrame] owns the whole frame lifecycle for one scanout buffer: it resets
 * and begins the buffer's command buffer, runs the caller's block to queue
 * commands, then ends and submits the recording, returning a [Submission] that
 * signals when the GPU has finished. Recording state is kept per buffer, so
 * interleaving the render loops of several monitors is safe as long as each
 * monitor keeps its own frames in flight.
 */
class VulkanRenderer : Renderer() {
    private val instance: VulkanInstance = try {
        VulkanInstance()
    } catch (e: VulkanErrorException) {
        throw UnsupportedPlatformException(feature = "Vulkan", cause = e)
    }

    override val deviceManager: VulkanDeviceManager = VulkanDeviceManager(instance)

    private val dispatcher = PollDispatcher("jayland-vulkan")
    private val commandPools = ConcurrentHashMap<VulkanGPU, Long>()
    private val commandBuffers = ConcurrentHashMap<Long, VkCommandBuffer>()
    private var closed = false

    override fun beginFrame(buffer: GPUScanoutBuffer, block: FrameRecording.() -> Unit): Submission {
        check(!closed) { "VulkanRenderer is closed" }
        val vBuffer = buffer as VulkanGPUScanoutBuffer
        val commandBuffer = commandBuffers.computeIfAbsent(vBuffer.vkImage) { commandBufferFor(vBuffer) }
        memStack {
            vkResetCommandBuffer(commandBuffer, 0).checkAsVkError("reset command buffer")
            vkBeginCommandBuffer(
                commandBuffer,
                VkCommandBufferBeginInfo.calloc(this).`sType$Default`(),
            ).checkAsVkError("begin command buffer")
        }
        try {
            VulkanFrameRecording(vBuffer, commandBuffer).block()
        } catch (t: Throwable) {
            // The equivalent of Zig's errdefer: the block failed mid-recording,
            // so discard the partial recording to keep the buffer reusable,
            // then propagate the original exception.
            vkResetCommandBuffer(commandBuffer, 0)
            throw t
        }
        return submitFrame(vBuffer, commandBuffer)
    }

    /** Ends the recording, submits it, and wraps the completion signal. */
    private fun submitFrame(buffer: VulkanGPUScanoutBuffer, commandBuffer: VkCommandBuffer): Submission {
        val gpu = buffer.owner
        memStack {
            vkEndCommandBuffer(commandBuffer).checkAsVkError("end command buffer")
            val semaphore = createExportableSemaphore(gpu.device)
            try {
                val submitInfo = VkSubmitInfo.calloc(this)
                    .`sType$Default`()
                    .pCommandBuffers(mallocPointer(1).put(0, commandBuffer.address()))
                    .pSignalSemaphores(longs(semaphore))
                vkQueueSubmit(gpu.queue, submitInfo, VK_NULL_HANDLE)
                    .checkAsVkError("submit frame for ${buffer.width}x${buffer.height}")
            } catch (t: Throwable) {
                // errdefer again: the semaphore was created but never signalled
                // by a successful submission, so destroy it before propagating.
                vkDestroySemaphore(gpu.device, semaphore, null)
                throw t
            }
            return VulkanSubmission(gpu, gpu.device, semaphore, dispatcher)
        }
    }

    private fun commandBufferFor(buffer: VulkanGPUScanoutBuffer): VkCommandBuffer {
        val pool = commandPools.computeIfAbsent(buffer.owner) { commandPoolFor(it) }
        return memStack {
            val handle = outPointer { buf ->
                vkAllocateCommandBuffers(
                    buffer.owner.device,
                    VkCommandBufferAllocateInfo.calloc(this)
                        .`sType$Default`()
                        .commandPool(pool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1),
                    buf,
                ).checkAsVkError("allocate command buffer")
            }
            VkCommandBuffer(handle, buffer.owner.device)
        }
    }

    private fun commandPoolFor(gpu: VulkanGPU): Long = memStack {
        outLong { buf ->
            vkCreateCommandPool(
                gpu.device,
                VkCommandPoolCreateInfo.calloc(this)
                    .`sType$Default`()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(gpu.queueFamilyIndex),
                null,
                buf,
            ).checkAsVkError("create command pool")
        }
    }

    private fun createExportableSemaphore(device: VkDevice): Long = memStack {
        outLong { buf ->
            vkCreateSemaphore(
                device,
                VkSemaphoreCreateInfo.calloc(this)
                    .`sType$Default`()
                    .pNext(
                        VkExportSemaphoreCreateInfo.calloc(this)
                            .`sType$Default`()
                            .handleTypes(VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT)
                            .address(),
                    ),
                null,
                buf,
            ).checkAsVkError("create exportable binary semaphore")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        commandBuffers.clear()
        commandPools.forEach { (gpu, pool) -> vkDestroyCommandPool(gpu.device, pool, null) }
        commandPools.clear()
        dispatcher.close()
        super.close()
    }
}
