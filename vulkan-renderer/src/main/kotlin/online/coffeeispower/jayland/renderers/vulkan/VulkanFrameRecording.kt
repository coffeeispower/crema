package online.coffeeispower.jayland.renderers.vulkan

import online.coffeeispower.jayland.core.Color
import online.coffeeispower.jayland.core.FrameRecording
import online.coffeeispower.jayland.core.GPU
import online.coffeeispower.jayland.core.GPUScanoutBuffer
import online.coffeeispower.jayland.lwjgl.memStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkClearColorValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkImageMemoryBarrier
import org.lwjgl.vulkan.VkImageSubresourceRange

/**
 * A [FrameRecording] that appends Vulkan commands to a command buffer. Every
 * command targets the recording's scanout buffer; the buffer is reset and
 * begun by [VulkanRenderer.beginFrame] and ended and submitted on its behalf.
 */
internal class VulkanFrameRecording(
    private val vBuffer: VulkanGPUScanoutBuffer,
    private val commandBuffer: VkCommandBuffer,
) : FrameRecording {

    override val buffer: GPUScanoutBuffer get() = vBuffer
    override val gpu: GPU get() = vBuffer.owner

    override fun clear(color: Color) {
        memStack {
            val range = VkImageSubresourceRange.calloc(this)
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1)

            val barriers = VkImageMemoryBarrier.calloc(1, this)
            barriers.get(0)
                .`sType$Default`()
                .srcAccessMask(0)
                .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(vBuffer.vkImage)
                .subresourceRange(range)
            vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                0,
                null,
                null,
                barriers,
            )

            val clearColor = VkClearColorValue.calloc(this)
                .float32(0, color.r)
                .float32(1, color.g)
                .float32(2, color.b)
                .float32(3, color.a)
            vkCmdClearColorImage(
                commandBuffer,
                vBuffer.vkImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                clearColor,
                range,
            )
        }
    }
}
