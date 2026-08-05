package online.coffeeispower.crema.renderers.vulkan

import online.coffeeispower.crema.core.graphics.renderer.FrameRecording
import online.coffeeispower.crema.core.graphics.gpu.GPUImageBuffer
import online.coffeeispower.crema.core.graphics.renderer.Renderer
import online.coffeeispower.crema.core.graphics.gpu.Submission
import online.coffeeispower.crema.lwjgl.memStack
import online.coffeeispower.crema.lwjgl.outLong
import online.coffeeispower.crema.lwjgl.outPointer
import online.coffeeispower.crema.utils.errors.UnsupportedPlatformException
import online.coffeeispower.crema.utils.fds.PollDispatcher
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.*
import java.util.concurrent.ConcurrentHashMap

/**
 * A [Renderer] that records each frame as graphics commands (a dynamic-rendering
 * pass against the scanout image) and submits them to a graphics queue,
 * signalling completion through an exportable binary semaphore per submission.
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

    private val dispatcher = PollDispatcher("crema-vulkan")
    private val commandPools = ConcurrentHashMap<VulkanGPU, Long>()
    private val commandBuffers = ConcurrentHashMap<Long, VkCommandBuffer>()
    private var closed = false

    override fun beginFrame(buffer: GPUImageBuffer, block: FrameRecording.() -> Unit): Submission {
        check(!closed) { "VulkanRenderer is closed" }
        val vBuffer = buffer as VulkanGPUImageBuffer
        val commandBuffer = commandBuffers.computeIfAbsent(vBuffer.vkImage) { commandBufferFor(vBuffer) }
        memStack {
            vkResetCommandBuffer(commandBuffer, 0).checkAsVkError("reset command buffer")
            vkBeginCommandBuffer(
                commandBuffer,
                VkCommandBufferBeginInfo.calloc(this).`sType$Default`(),
            ).checkAsVkError("begin command buffer")
            transitionToColorAttachment(commandBuffer, vBuffer)
            beginRendering(commandBuffer, vBuffer)
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
    private fun submitFrame(buffer: VulkanGPUImageBuffer, commandBuffer: VkCommandBuffer): Submission {
        val gpu = buffer.owner
        memStack {
            vkCmdEndRendering(commandBuffer)
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

    //<editor-fold desc="Command buffer and semaphore boilerplate">
    /** Transitions the scanout image into color-attachment state for rendering. */
    private fun transitionToColorAttachment(commandBuffer: VkCommandBuffer, buffer: VulkanGPUImageBuffer) {
        memStack {
            val barriers = VkImageMemoryBarrier2.calloc(1, this)
            barriers.get(0)
                .`sType$Default`()
                .srcStageMask(VK_PIPELINE_STAGE_2_NONE)
                .srcAccessMask(0)
                .dstStageMask(VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT)
                .dstAccessMask(VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(buffer.vkImage)
                .subresourceRange(
                    VkImageSubresourceRange.calloc(this)
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1),
                )
            val dependencyInfo = VkDependencyInfo.calloc(this)
                .`sType$Default`()
                .pImageMemoryBarriers(barriers)
            vkCmdPipelineBarrier2(commandBuffer, dependencyInfo)
        }
    }

    /**
     * Opens a dynamic-rendering pass over the whole buffer and sets the dynamic
     * viewport/scissor so the (per-format, not per-size) shape pipeline can be
     * shared across buffers of different sizes.
     */
    private fun beginRendering(commandBuffer: VkCommandBuffer, buffer: VulkanGPUImageBuffer) {
        memStack {
            val attachments = VkRenderingAttachmentInfo.calloc(1, this)
            attachments.get(0)
                .`sType$Default`()
                .imageView(buffer.vkImageView)
                .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .loadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
            val renderingInfo = VkRenderingInfo.calloc(this)
                .`sType$Default`()
                .renderArea(
                    VkRect2D.calloc(this)
                        .extent(VkExtent2D.calloc(this).width(buffer.width).height(buffer.height)),
                )
                .layerCount(1)
                .pColorAttachments(attachments)
            vkCmdBeginRendering(commandBuffer, renderingInfo)
            val viewports = VkViewport.calloc(1, this)
            viewports.get(0)
                .x(0f).y(0f)
                .width(buffer.width.toFloat()).height(buffer.height.toFloat())
                .minDepth(0f).maxDepth(1f)
            val scissors = VkRect2D.calloc(1, this)
            scissors.get(0)
                .offset(VkOffset2D.calloc(this))
                .extent(VkExtent2D.calloc(this).width(buffer.width).height(buffer.height))
            vkCmdSetViewport(commandBuffer, 0, viewports)
            vkCmdSetScissor(commandBuffer, 0, scissors)
        }
    }

    private fun commandBufferFor(buffer: VulkanGPUImageBuffer): VkCommandBuffer {
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
        // Spec-safe teardown order: vkDestroyCommandPool is undefined behavior
        // if any of the pool's command buffers are still pending, so idle every
        // GPU that owns a pool before destroying anything device-bound. This
        // also covers the images vram.close() frees during gpu.close() below.
        commandPools.keys.forEach { gpu ->
            vkDeviceWaitIdle(gpu.device).checkAsVkError("wait for device idle on ${gpu.name}")
        }
        commandBuffers.clear()
        commandPools.forEach { (gpu, pool) -> vkDestroyCommandPool(gpu.device, pool, null) }
        commandPools.clear()
        dispatcher.close()
        super.close()
    }
    //</editor-fold>
}
