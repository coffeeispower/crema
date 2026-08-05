package online.coffeeispower.crema.renderers.vulkan

import online.coffeeispower.crema.lwjgl.memStack
import online.coffeeispower.crema.lwjgl.outLong
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VkDevice
import org.lwjgl.vulkan.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * The solid-shape pipeline: one vertex + fragment shader pair that draws
 * axis-aligned rectangles — optionally rounded, optionally with a border —
 * entirely from push constants. No vertex buffers, descriptors or render
 * passes: the quad is generated in the vertex shader and the fragment shader
 * shades it from the rounded-box SDF, so [drawRect]/[drawRectBorder] are single
 * draws.
 *
 * One [VkPipeline] is created per scanout format on first use and presented via
 * Vulkan 1.3 dynamic rendering ([VkPipelineRenderingCreateInfo] in `pNext`), so
 * it can render into any [online.coffeeispower.crema.core.graphics.ColorMode]
 * buffer regardless of its tiling or modifier.
 *
 * Device-scoped: [close] must run before the owning [VulkanGPU]'s logical
 * device is destroyed.
 */
internal class VulkanShapePipeline(private val gpu: VulkanGPU) : AutoCloseable {

    private val device: VkDevice get() = gpu.device

    /** Pipeline layout for the push-constant-only shape shaders. */
    val layout: Long = createPipelineLayout()

    private val vertexModule = createShaderModule(readSpirv("/shaders/shape.vert.spv"))
    private val fragmentModule = createShaderModule(readSpirv("/shaders/shape.frag.spv"))
    private val pipelines = ConcurrentHashMap<Int, Long>()
    private var closed = false

    init {
        check(gpu.supportsVulkan13) {
            "Vulkan 1.3 (dynamic rendering) is required for shape rendering on ${gpu.name}"
        }
    }

    /** The pipeline that renders into images of [vkFormat], created on first use. */
    fun pipelineFor(vkFormat: Int): Long = pipelines.computeIfAbsent(vkFormat) { createPipeline(it) }

    private fun createPipeline(vkFormat: Int): Long = memStack {
        val stages = VkPipelineShaderStageCreateInfo.calloc(2, this)
        stages.get(0)
            .`sType$Default`()
            .stage(VK_SHADER_STAGE_VERTEX_BIT)
            .module(vertexModule)
            .pName(UTF8("main"))
        stages.get(1)
            .`sType$Default`()
            .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
            .module(fragmentModule)
            .pName(UTF8("main"))

        val dynamicState = VkPipelineDynamicStateCreateInfo.calloc(this)
            .`sType$Default`()
            .pDynamicStates(ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR))

        val vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(this).`sType$Default`()
        val inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(this)
            .`sType$Default`()
            .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP)
            .primitiveRestartEnable(false)

        val viewportState = VkPipelineViewportStateCreateInfo.calloc(this)
            .`sType$Default`()
            .viewportCount(1)
            .scissorCount(1)

        val rasterization = VkPipelineRasterizationStateCreateInfo.calloc(this)
            .`sType$Default`()
            .depthClampEnable(false)
            .rasterizerDiscardEnable(false)
            .polygonMode(VK_POLYGON_MODE_FILL)
            .cullMode(VK_CULL_MODE_NONE)
            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
            .depthBiasEnable(false)
            .lineWidth(1.0f)

        val multisample = VkPipelineMultisampleStateCreateInfo.calloc(this)
            .`sType$Default`()
            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
            .sampleShadingEnable(false)

        // Straight (non-premultiplied) alpha blending so the smoothstep coverage
        // anti-aliases rounded edges instead of showing hard pixel boundaries.
        val blendAttachment = VkPipelineColorBlendAttachmentState.calloc(1, this)
        blendAttachment.get(0)
            .blendEnable(true)
            .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
            .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
            .colorBlendOp(VK_BLEND_OP_ADD)
            .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
            .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
            .alphaBlendOp(VK_BLEND_OP_ADD)
            .colorWriteMask(
                VK_COLOR_COMPONENT_R_BIT or VK_COLOR_COMPONENT_G_BIT or
                    VK_COLOR_COMPONENT_B_BIT or VK_COLOR_COMPONENT_A_BIT,
            )
        val colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(this)
            .`sType$Default`()
            .logicOpEnable(false)
            .pAttachments(blendAttachment)

        // Dynamic rendering: no VkRenderPass/VkFramebuffer needed (Vulkan 1.3).
        val renderingInfo = VkPipelineRenderingCreateInfo.calloc(this)
            .`sType$Default`()
            .colorAttachmentCount(1)
            .pColorAttachmentFormats(ints(vkFormat))

        val createInfo = VkGraphicsPipelineCreateInfo.calloc(1, this)
        createInfo.get(0)
            .`sType$Default`()
            .pNext(renderingInfo)
            .stageCount(2)
            .pStages(stages)
            .pVertexInputState(vertexInput)
            .pInputAssemblyState(inputAssembly)
            .pViewportState(viewportState)
            .pRasterizationState(rasterization)
            .pMultisampleState(multisample)
            .pDepthStencilState(null)
            .pColorBlendState(colorBlend)
            .pDynamicState(dynamicState)
            .layout(layout)
            .renderPass(VK_NULL_HANDLE)
            .subpass(0)

        outLong { buf ->
            vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, createInfo, null, buf)
                .checkAsVkError("create shape pipeline for ${vkFormatName(vkFormat)}")
        }
    }

    private fun createPipelineLayout(): Long = memStack {
        val ranges = VkPushConstantRange.calloc(1, this)
        ranges.get(0)
            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT)
            .offset(0)
            .size(PUSH_CONSTANT_SIZE)
        outLong { buf ->
            vkCreatePipelineLayout(
                device,
                VkPipelineLayoutCreateInfo.calloc(this).`sType$Default`().pPushConstantRanges(ranges),
                null,
                buf,
            ).checkAsVkError("create shape pipeline layout")
        }
    }

    private fun createShaderModule(code: ByteBuffer): Long = memStack {
        outLong { buf ->
            vkCreateShaderModule(
                device,
                VkShaderModuleCreateInfo.calloc(this).`sType$Default`().pCode(code),
                null,
                buf,
            ).checkAsVkError("create shape shader module")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        pipelines.values.forEach { vkDestroyPipeline(device, it, null) }
        pipelines.clear()
        vkDestroyPipelineLayout(device, layout, null)
        vkDestroyShaderModule(device, vertexModule, null)
        vkDestroyShaderModule(device, fragmentModule, null)
    }

    companion object {
        /** Byte size of the shape push-constant block (13 floats, std140-valid). */
        const val PUSH_CONSTANT_SIZE = 52
    }
}

/** Loads a committed SPIR-V resource as a 4-byte-aligned buffer for [VkShaderModuleCreateInfo]. */
private fun readSpirv(resource: String): ByteBuffer {
    val bytes = checkNotNull(VulkanShapePipeline::class.java.getResourceAsStream(resource)) {
        "Missing SPIR-V resource $resource (did compileSpirv run?)"
    }.use { it.readBytes() }
    return MemoryUtil.memAlloc(bytes.size).put(bytes).flip()
}

private fun vkFormatName(format: Int): String = when (format) {
    VK_FORMAT_B8G8R8A8_UNORM -> "B8G8R8A8_UNORM"
    VK_FORMAT_A2B10G10R10_UNORM_PACK32 -> "A2B10G10R10_UNORM_PACK32"
    VK_FORMAT_R16G16B16A16_SFLOAT -> "R16G16B16A16_SFLOAT"
    VK_FORMAT_R32G32B32A32_SFLOAT -> "R32G32B32A32_SFLOAT"
    else -> "VkFormat $format"
}
