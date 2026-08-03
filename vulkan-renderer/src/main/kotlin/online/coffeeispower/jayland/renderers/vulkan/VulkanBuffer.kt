package online.coffeeispower.jayland.renderers.vulkan

import online.coffeeispower.jayland.core.ColorMode
import online.coffeeispower.jayland.core.GPUScanoutBuffer
import online.coffeeispower.jayland.core.VRam
import online.coffeeispower.jayland.core.platform.linux.DrmScanoutBuffer
import online.coffeeispower.jayland.lwjgl.memStack
import online.coffeeispower.jayland.lwjgl.outInt
import online.coffeeispower.jayland.lwjgl.outLong
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceImageFormatProperties2
import org.lwjgl.vulkan.*

/** Scanout-relevant DRM format fourccs and modifiers (not available via jextract, they are C macros). */
internal object DrmFormats {
    const val XRGB8888 = 0x34325258
    const val XRGB2101010 = 0x30335258
    const val MOD_LINEAR = 1L
    const val MOD_INVALID = 0L
}

private val SCANOUT_USAGE =
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
        VK_IMAGE_USAGE_TRANSFER_DST_BIT or
        VK_IMAGE_USAGE_SAMPLED_BIT

class VulkanVRam(private val gpu: VulkanGPU) : VRam {

    private val buffers = java.util.Collections.synchronizedList(mutableListOf<VulkanGPUScanoutBuffer>())

    override fun allocateBufferForScanout(width: Int, height: Int, colorMode: ColorMode): GPUScanoutBuffer {
        val device = gpu.device
        val vkFormat = colorMode.vkFormat
        return memStack {
            val modifier = gpu.pickScanoutModifier(vkFormat, SCANOUT_USAGE)
            val externalInfo = VkExternalMemoryImageCreateInfo.calloc(this)
                .`sType$Default`()
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
            if (modifier != DrmFormats.MOD_INVALID) {
                externalInfo.pNext(
                    VkImageDrmFormatModifierListCreateInfoEXT.calloc(this)
                        .`sType$Default`()
                        .pDrmFormatModifiers(longs(modifier))
                        .address(),
                )
            }
            val image = outLong { buf ->
                val createInfo = VkImageCreateInfo.calloc(this)
                    .`sType$Default`()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(vkFormat)
                    .extent(VkExtent3D.calloc(this).width(width).height(height).depth(1))
                    .mipLevels(1)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(
                        if (modifier != DrmFormats.MOD_INVALID) {
                            EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT
                        } else {
                            VK_IMAGE_TILING_OPTIMAL
                        },
                    )
                    .usage(SCANOUT_USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .pNext(externalInfo.address())
                vkCreateImage(device, createInfo, null, buf)
                    .checkAsVkError("create image ${width}x$height $colorMode")
            }
            val requirements = VkMemoryRequirements.malloc(this)
            vkGetImageMemoryRequirements(device, image, requirements)
            val memoryProperties = VkPhysicalDeviceMemoryProperties.malloc(this)
            vkGetPhysicalDeviceMemoryProperties(gpu.physicalDevice, memoryProperties)
            val memoryTypeIndex = pickMemoryType(memoryProperties, requirements.memoryTypeBits())
            val memory = outLong { buf ->
                val allocInfo = VkMemoryAllocateInfo.calloc(this)
                    .`sType$Default`()
                    .allocationSize(requirements.size())
                    .memoryTypeIndex(memoryTypeIndex)
                    .pNext(
                        VkExportMemoryAllocateInfo.calloc(this)
                            .`sType$Default`()
                            .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
                            .address(),
                    )
                vkAllocateMemory(device, allocInfo, null, buf)
                    .checkAsVkError("allocate image memory ${width}x$height")
            }
            vkBindImageMemory(device, image, memory, 0).checkAsVkError("bind image memory")
            VulkanGPUScanoutBuffer(
                width, height, colorMode, gpu, device, image, memory,
                colorMode.drmFormat, modifier,
            ).also { buffers.add(it) }
        }
    }

    override fun close() {
        buffers.forEach { it.close() }
        buffers.clear()
    }
}

private fun pickMemoryType(memoryProperties: VkPhysicalDeviceMemoryProperties, memoryTypeBits: Int): Int {
    // Device-local is required for a drawable that can be scanned out; fall back
    // to host-visible memory on GPUs without a suitable device-local type.
    val candidates = listOf(
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
    )
    for (required in candidates) {
        (0 until memoryProperties.memoryTypeCount()).firstOrNull {
            (memoryTypeBits shr it) and 1 != 0 &&
                memoryProperties.memoryTypes(it).propertyFlags() and required == required
        }?.let { return it }
    }
    error("No suitable memory type available")
}

private val ColorMode.vkFormat: Int
    get() = when (this) {
        ColorMode.RGBA8 -> VK_FORMAT_R8G8B8A8_UNORM
        ColorMode.RGB10A2 -> VK_FORMAT_A2B10G10R10_UNORM_PACK32
        ColorMode.RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT
        ColorMode.RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT
    }

private val ColorMode.drmFormat: Int
    get() = when (this) {
        ColorMode.RGBA8 -> DrmFormats.XRGB8888
        ColorMode.RGB10A2 -> DrmFormats.XRGB2101010
        ColorMode.RGBA16F, ColorMode.RGBA32F -> DrmFormats.XRGB8888
    }

class VulkanGPUScanoutBuffer(
    override val width: Int,
    override val height: Int,
    override val colorMode: ColorMode,
    override val owner: VulkanGPU,
    private val device: VkDevice,
    private val image: Long,
    private val memory: Long,
    override val drmFormat: Int,
    override val drmModifier: Long,
) : DrmScanoutBuffer {

    internal val vkImage: Long get() = image

    override val stride: Int = memStack {
        val subresource = VkImageSubresource.calloc(this)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0)
            .arrayLayer(0)
        val layout = VkSubresourceLayout.malloc(this)
        vkGetImageSubresourceLayout(device, image, subresource, layout)
        if (layout.rowPitch() > 0) {
            layout.rowPitch().toInt()
        } else {
            // Not a linear image (implicit tiling): fall back to a packed pitch
            // so the KMS import still has a sane value to report.
            width * (colorMode.bitsPerPixel / 8)
        }
    }

    private var closed = false

    override fun exportDmaBufFd(): Int = memStack {
        outInt { buf ->
            KHRExternalMemoryFd.vkGetMemoryFdKHR(
                device,
                VkMemoryGetFdInfoKHR.calloc(this)
                    .`sType$Default`()
                    .memory(memory)
                    .handleType(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT),
                buf,
            ).checkAsVkError("export dma-buf fd")
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        vkDestroyImage(device, image, null)
        vkFreeMemory(device, memory, null)
    }
}
