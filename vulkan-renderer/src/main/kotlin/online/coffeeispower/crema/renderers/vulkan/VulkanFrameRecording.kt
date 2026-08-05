package online.coffeeispower.crema.renderers.vulkan

import online.coffeeispower.crema.core.graphics.Border
import online.coffeeispower.crema.core.graphics.Color
import online.coffeeispower.crema.core.graphics.Rectangle
import online.coffeeispower.crema.core.graphics.RoundedRectangle
import online.coffeeispower.crema.core.graphics.renderer.FrameRecording
import online.coffeeispower.crema.core.graphics.gpu.GPU
import online.coffeeispower.crema.core.graphics.gpu.GPUImageBuffer
import online.coffeeispower.crema.lwjgl.memStack
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkCommandBuffer

/**
 * A [FrameRecording] that appends Vulkan commands to a command buffer. Every
 * command targets the recording's image buffer; the buffer is reset and
 * begun by [VulkanRenderer.beginFrame] and ended and submitted on its behalf.
 *
 * Draws go through the [VulkanShapePipeline], which renders rectangles (flat or
 * rounded, with or without a border) from a single push-constant block — no
 * vertex buffers, descriptors or render passes are involved.
 */
internal class VulkanFrameRecording(
    private val vBuffer: VulkanGPUImageBuffer,
    private val commandBuffer: VkCommandBuffer,
) : FrameRecording {

    override val buffer: GPUImageBuffer get() = vBuffer
    override val gpu: GPU get() = vBuffer.owner

    override fun clear(color: Color) = drawRect(
        Rectangle(x = 0f, y = 0f, width = vBuffer.width.toFloat(), height = vBuffer.height.toFloat()),
        fillColor = color,
    )

    override fun drawRect(rect: Rectangle, fillColor: Color) = drawRectangleInternal(
        x = rect.x, y = rect.y, width = rect.width, height = rect.height,
        color = fillColor, radius = 0f, borderWidth = 0f, borderMode = 0,
    )

    override fun drawRect(rect: RoundedRectangle, fillColor: Color) = drawRectangleInternal(
        x = rect.x, y = rect.y, width = rect.width, height = rect.height,
        color = fillColor, radius = rect.radius, borderWidth = 0f, borderMode = 0,
    )

    override fun drawRectBorder(rect: Rectangle, border: Border) = drawRectangleInternal(
        x = rect.x, y = rect.y, width = rect.width, height = rect.height,
        color = border.borderColor, radius = 0f,
        borderWidth = border.borderWidth, borderMode = border.borderMode.ordinal + 1,
    )

    override fun drawRectBorder(rect: RoundedRectangle, border: Border) = drawRectangleInternal(
        x = rect.x, y = rect.y, width = rect.width, height = rect.height,
        color = border.borderColor, radius = rect.radius,
        borderWidth = border.borderWidth, borderMode = border.borderMode.ordinal + 1,
    )

    /**
     * Records a single draw that both generates the quad (vertex shader) and
     * shades it (fragment shader) entirely from push constants.
     *
     * [borderMode] is 0 for a filled rect and 1/2/3 for outside/middle/inside
     * borders, matching the shader's sentinel convention.
     */
    private fun drawRectangleInternal(
        x: Float, y: Float, width: Float, height: Float,
        color: Color, radius: Float, borderWidth: Float, borderMode: Int,
    ) {
        val pipeline = vBuffer.owner.shapePipeline()
        memStack {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineFor(vBuffer.vkFormat))
            vkCmdPushConstants(
                commandBuffer,
                pipeline.layout,
                VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT,
                0,
                floats(
                    x, y,
                    width, height,
                    color.r, color.g, color.b, color.a,
                    vBuffer.width.toFloat(), vBuffer.height.toFloat(),
                    radius, borderWidth, borderMode.toFloat(),
                ),
            )
            vkCmdDraw(commandBuffer, 4, 1, 0, 0)
        }
    }
}
