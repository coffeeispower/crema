package online.coffeeispower.crema.renderers.vulkan

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.core.graphics.ColorMode
import online.coffeeispower.crema.core.platform.linux.GPUScanoutBuffer
import online.coffeeispower.crema.core.graphics.gpu.VRam
import online.coffeeispower.crema.core.platform.linux.DrmScanoutBuffer
import online.coffeeispower.crema.drm.sys.DrmFormats
import online.coffeeispower.crema.drm.sys.drmFourcc
import online.coffeeispower.crema.lwjgl.memStack
import online.coffeeispower.crema.lwjgl.outInt
import online.coffeeispower.crema.lwjgl.outLong
import org.lwjgl.vulkan.EXTExternalMemoryDmaBuf.VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT
import org.lwjgl.vulkan.EXTImageDrmFormatModifier
import org.lwjgl.vulkan.KHRExternalMemoryFd
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.*

/**
 * How a scanout image is created and the tiling modifier reported to KMS.
 *
 * [vkImageTiling] is `VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT`,
 * [VK_IMAGE_TILING_LINEAR] or [VK_IMAGE_TILING_OPTIMAL]; [drmModifier] is
 * [DrmFormats.MOD_LINEAR] when the buffer is linear, [DrmFormats.MOD_INVALID]
 * otherwise.
 */
data class ScanoutTiling(val vkImageTiling: Int, val drmModifier: Long)

private val SCANOUT_USAGE =
    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT or
        VK_IMAGE_USAGE_TRANSFER_DST_BIT or
        VK_IMAGE_USAGE_SAMPLED_BIT

class VulkanVRam(private val gpu: VulkanGPU) : VRam {

    private val logger = KotlinLogging.logger {}
    private val buffers = java.util.Collections.synchronizedList(mutableListOf<VulkanGPUScanoutBuffer>())

    override fun allocateBufferForScanout(
        width: Int,
        height: Int,
        colorMode: ColorMode,
        allowedModifiers: List<Long>?,
    ): GPUScanoutBuffer {
        val device = gpu.device
        val vkFormat = colorMode.vkFormat
        return memStack {
            val tiling = gpu.pickScanoutTiling(vkFormat, SCANOUT_USAGE, allowedModifiers)
            val externalInfo = VkExternalMemoryImageCreateInfo.calloc(this)
                .`sType$Default`()
                .handleTypes(VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT)
            val stride = if (tiling.vkImageTiling == EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT) {
                val pitch = if (tiling.drmModifier == DrmFormats.MOD_LINEAR) {
                    alignedPitch(width, colorMode.bitsPerPixel / 8, 64)
                } else {
                    scanoutPitch(tiling.drmModifier, width, colorMode.bitsPerPixel / 8)
                }
                externalInfo.pNext(
                    VkImageDrmFormatModifierExplicitCreateInfoEXT.calloc(this)
                        .`sType$Default`()
                        .drmFormatModifier(tiling.drmModifier)
                        .pPlaneLayouts(VkSubresourceLayout.calloc(1, this).rowPitch(pitch.toLong()))
                        .address(),
                )
                pitch
            } else {
                0
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
                    .tiling(tiling.vkImageTiling)
                    .usage(SCANOUT_USAGE)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .pNext(externalInfo.address())
                vkCreateImage(device, createInfo, null, buf)
                    .checkAsVkError("create image ${width}x$height $colorMode")
            }
            if (tiling.vkImageTiling == EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT) {
                logActualDrmModifier(device, image, tiling.drmModifier)
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
                colorMode.drmFourcc, tiling.drmModifier,
                tiling.vkImageTiling == EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
                if (stride > 0) stride else linearRowPitch(device, image),
            ).also { buffers.add(it) }
        }
    }

    private fun logActualDrmModifier(device: VkDevice, image: Long, requested: Long) = memStack {
        val props = VkImageDrmFormatModifierPropertiesEXT.calloc(this).`sType$Default`()
        val ret = EXTImageDrmFormatModifier.vkGetImageDrmFormatModifierPropertiesEXT(device, image, props)
        val actual = if (ret == VK_SUCCESS) props.drmFormatModifier() else -1L
        logger.debug { "Scanout image DRM modifier: requested=$requested actual=$actual (query=$ret)" }
        if (ret == VK_SUCCESS && actual != requested) {
            logger.warn { "Driver assigned modifier $actual instead of requested $requested; KMS may reject the buffer" }
        }
    }

    /** The real scanline pitch of a linear image (valid layout query). */
    private fun linearRowPitch(device: VkDevice, image: Long): Int = memStack {
        val subresource = VkImageSubresource.calloc(this)
            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
            .mipLevel(0)
            .arrayLayer(0)
        val layout = VkSubresourceLayout.malloc(this)
        vkGetImageSubresourceLayout(device, image, subresource, layout)
        check(layout.rowPitch() > 0) { "Linear image has no row pitch" }
        layout.rowPitch().toInt()
    }

    override fun close() {
        buffers.forEach { it.close() }
        buffers.clear()
    }
}

/**
 * The scanline pitch (in bytes) a single-plane modifier requires, so the layout
 * the image is created with and the pitch reported to KMS always agree. Intel
 * tile rows are 512B wide for X tiling and 128B for Y/Yf; compressed (CCS)
 * surfaces need a multiple of four tile widths, which 512 satisfies. Other
 * vendors get the conservative 512B tile-row alignment — the driver validates
 * the layout at `vkCreateImage` time and rejects an invalid pitch.
 */
private fun scanoutPitch(modifier: Long, width: Int, bytesPerPixel: Int): Int {
    val alignment = if (modifier shr 56 == DrmFormats.MOD_VENDOR_INTEL) {
        when ((modifier and 0xffff).toInt()) {
            DrmFormats.I915_Y_TILED, DrmFormats.I915_YF_TILED -> 128
            else -> 512
        }
    } else {
        512
    }
    return alignedPitch(width, bytesPerPixel, alignment)
}

private fun alignedPitch(width: Int, bytesPerPixel: Int, alignment: Int): Int {
    val rowBytes = width * bytesPerPixel
    return (rowBytes + alignment - 1) / alignment * alignment
}

private fun pickMemoryType(memoryProperties: VkPhysicalDeviceMemoryProperties, memoryTypeBits: Int): Int {
    // Prefer device-local memory that is also host-visible and coherent: on
    // Intel this is the smem region whose dma-bufs scan out correctly AND can
    // be CPU-mapped for readback/software paths. Fall back to device-local-only
    // memory, then to any host-visible/coherent type.
    val candidates = listOf(
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT or VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT or VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
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
        // XRGB8888 stores bytes as [B, G, R, X], so scanout buffers must be
        // B8G8R8A8 (NOT R8G8B8A8, which would swap red and blue on the wire).
        ColorMode.RGBA8 -> VK_FORMAT_B8G8R8A8_UNORM
        ColorMode.RGB10A2 -> VK_FORMAT_A2B10G10R10_UNORM_PACK32
        ColorMode.RGBA16F -> VK_FORMAT_R16G16B16A16_SFLOAT
        ColorMode.RGBA32F -> VK_FORMAT_R32G32B32A32_SFLOAT
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
    override val usesExplicitModifier: Boolean,
    override val stride: Int,
) : DrmScanoutBuffer {

    internal val vkImage: Long get() = image

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
