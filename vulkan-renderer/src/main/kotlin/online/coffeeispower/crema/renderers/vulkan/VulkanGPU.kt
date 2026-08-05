package online.coffeeispower.crema.renderers.vulkan

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.core.platform.linux.DrmGPU
import online.coffeeispower.crema.core.platform.linux.DrmProps
import online.coffeeispower.crema.core.graphics.gpu.VRam
import online.coffeeispower.crema.drm.sys.DrmFormats
import online.coffeeispower.crema.lwjgl.CStr
import online.coffeeispower.crema.lwjgl.memStack
import online.coffeeispower.crema.lwjgl.outInt
import online.coffeeispower.crema.lwjgl.outPointer
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceImageFormatProperties2
import org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2

class VulkanGPU(
    val physicalDevice: VkPhysicalDevice,
    val instance: VulkanInstance,
) : DrmGPU {
    val logger = KotlinLogging.logger {}

    val hasDrmDeviceExtension: Boolean
        get() = "VK_EXT_physical_device_drm" in deviceExtensions

    override val drmProps: DrmProps by lazy {
        if (hasDrmDeviceExtension) {
            memStack {
                val drmProps = VkPhysicalDeviceDrmPropertiesEXT.calloc(this)
                    .`sType$Default`()

                val props2 = VkPhysicalDeviceProperties2.calloc(this)
                    .`sType$Default`()
                    .pNext(drmProps)

                vkGetPhysicalDeviceProperties2(physicalDevice, props2)
                DrmProps(
                    render = if (drmProps.hasRender()) Pair(
                        drmProps.renderMajor(),
                        drmProps.renderMinor()
                    ) else null,
                    primary = if (drmProps.hasPrimary()) Pair(
                        drmProps.primaryMajor(),
                        drmProps.primaryMinor()
                    ) else null
                )
            }
        } else DrmProps(null, null)
    }
    private var closed = false

    private val lazyDevice = lazy { queryLogicalDevice() }
    val device: VkDevice by lazyDevice

    override val name: String by lazy {
        memStack {
            val properties = VkPhysicalDeviceProperties.calloc(this)
            vkGetPhysicalDeviceProperties(physicalDevice, properties)
            properties.deviceNameString()
        }
    }

    /** The physical device's advertised Vulkan API version. */
    private val apiVersion: Int by lazy {
        memStack {
            val properties = VkPhysicalDeviceProperties.calloc(this)
            vkGetPhysicalDeviceProperties(physicalDevice, properties)
            properties.apiVersion()
        }
    }

    /** Whether the device supports core Vulkan 1.3 (dynamic rendering). */
    internal val supportsVulkan13: Boolean by lazy { apiVersionAtLeast(1, 3) }

    /**
     * Whether the device's advertised [apiVersion] is at least [major].[minor].[patch].
     */
    private fun apiVersionAtLeast(major: Int, minor: Int, patch: Int = 0): Boolean {
        val deviceMajor = VK_API_VERSION_MAJOR(apiVersion)
        val deviceMinor = VK_API_VERSION_MINOR(apiVersion)
        val devicePatch = VK_API_VERSION_PATCH(apiVersion)
        return deviceMajor > major ||
            deviceMajor == major && (deviceMinor > minor ||
                deviceMinor == minor && devicePatch >= patch)
    }

    private val shapePipelineLock = Any()
    private var shapePipeline: VulkanShapePipeline? = null

    /** The solid-shape pipeline for this GPU, created lazily on first draw. */
    internal fun shapePipeline(): VulkanShapePipeline = synchronized(shapePipelineLock) {
        shapePipeline ?: VulkanShapePipeline(this).also { shapePipeline = it }
    }

    override val vram: VRam = VulkanVRam(this)

    private val deviceExtensions: List<String> by lazy {
        memStack {
            val count = outInt { vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, it, null) }
            val properties = VkExtensionProperties.calloc(count, this)
            vkEnumerateDeviceExtensionProperties(physicalDevice, null as String?, intArrayOf(count), properties)
            List(count) { properties.get(it).extensionNameString() }
        }
    }

    private val requestedExtensions: List<String> by lazy {
        buildList {
            add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME)
            val os = System.getProperty("os.name", "generic").lowercase()
            if (("mac" in os || "darwin" in os) &&
                KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME in deviceExtensions
            ) {
                add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME).also {
                    logger.debug { "Requesting portability subset for $name" }
                }
            }
            // This is needed so the DRMBlitTarget knows which physical devices correspond to what render nodes
            if (hasDrmDeviceExtension) add("VK_EXT_physical_device_drm")
            // Needed to export the scanout buffers and the submission signals as DMA-BUF / sync-fds for KMS.
            if (KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME in deviceExtensions) {
                add(KHRExternalMemoryFd.VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME)
            }
            if (KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME in deviceExtensions) {
                add(KHRExternalSemaphoreFd.VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME)
            }
            if (EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME in deviceExtensions) {
                add(EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME)
            }
        }
    }

    /** Index of the first graphics queue family on this device. */
    val queueFamilyIndex: Int by lazy {
        memStack {
            val count = outInt { vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, it, null) }
            val properties = VkQueueFamilyProperties.malloc(count, this)
            vkGetPhysicalDeviceQueueFamilyProperties(physicalDevice, intArrayOf(count), properties)
            List(count) { properties.get(it) }
                .indexOfFirst { it.queueFlags() and VK_QUEUE_GRAPHICS_BIT != 0 }
                .also { index -> check(index != -1) { "No graphics queue family on $name" } }
        }
    }

    /** The first queue of the graphics queue family, used for all submissions. */
    val queue: VkQueue by lazy {
        memStack {
            val handle = outPointer { buf ->
                vkGetDeviceQueue(device, queueFamilyIndex, 0, buf)
            }
            VkQueue(handle, device)
        }
    }

    /**
     * Chooses how scanout images are created and what modifier to report to KMS.
     *
     * When the output constrains the layout ([allowedModifiers]), a plain
     * `VK_IMAGE_TILING_LINEAR` image is preferred whenever LINEAR is allowed:
     * its pitch is knowable exactly (vkGetImageSubresourceLayout), it is
     * single-plane (no CCS aux surface to describe in the framebuffer) and every
     * plane listing LINEAR in its `IN_FORMATS` accepts it. Otherwise the first
     * tiled modifier the device can produce is used with a
     * `VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT` image (created with an explicit
     * layout, so the exported DMA-BUF carries the modifier and the KMS pitch is
     * deterministic). If only [DrmFormats.MOD_LINEAR] is acceptable it may be
     * produced through the extension instead; any other unsatisfiable constraint
     * is an error, because silently creating an image the output cannot scan out
     * is worse than failing enable.
     *
     * With no constraint it prefers a modifier image carrying
     * [DrmFormats.MOD_LINEAR], then a plain linear image, and last resort is
     * implicit optimal tiling (whose exported buffer does not scan out).
     */
    fun pickScanoutTiling(format: Int, usage: Int, allowedModifiers: List<Long>? = null): ScanoutTiling {
        val required = allowedModifiers.orEmpty()
        if (required.isNotEmpty()) {
            if (DrmFormats.MOD_LINEAR in required && isTilingSupported(format, usage, VK_IMAGE_TILING_LINEAR)) {
                logger.debug { "Output-required LINEAR satisfied with plain linear tiling for format $format" }
                return ScanoutTiling(VK_IMAGE_TILING_LINEAR, DrmFormats.MOD_LINEAR)
            }
            if (EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME in deviceExtensions) {
                for (modifier in required) {
                    if (modifier == DrmFormats.MOD_LINEAR) continue
                    if (isTilingSupported(format, usage, modifier)) {
                        logger.debug { "Output-required DRM modifier $modifier supported for format $format" }
                        return ScanoutTiling(
                            EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
                            modifier,
                        )
                    }
                }
                if (DrmFormats.MOD_LINEAR in required && isTilingSupported(format, usage, DrmFormats.MOD_LINEAR)) {
                    logger.debug { "Output-required LINEAR produced via DRM modifier extension for format $format" }
                    return ScanoutTiling(
                        EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
                        DrmFormats.MOD_LINEAR,
                    )
                }
            }
            error("None of the output-required scanout modifiers $required can be produced for format $format")
        }
        if (EXTImageDrmFormatModifier.VK_EXT_IMAGE_DRM_FORMAT_MODIFIER_EXTENSION_NAME in deviceExtensions &&
            isTilingSupported(format, usage, DrmFormats.MOD_LINEAR)
        ) {
            logger.debug { "DRM_FORMAT_MOD_LINEAR supported for format $format" }
            return ScanoutTiling(
                EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT,
                DrmFormats.MOD_LINEAR,
            )
        }
        if (isTilingSupported(format, usage, VK_IMAGE_TILING_LINEAR)) {
            logger.debug { "Plain linear tiling supported for format $format" }
            return ScanoutTiling(VK_IMAGE_TILING_LINEAR, DrmFormats.MOD_LINEAR)
        }
        logger.warn { "Linear tiling not supported for format $format, falling back to implicit optimal tiling" }
        return ScanoutTiling(VK_IMAGE_TILING_OPTIMAL, DrmFormats.MOD_INVALID)
    }

    private fun isTilingSupported(format: Int, usage: Int, modifier: Long): Boolean = memStack {
        val modifierInfo = VkPhysicalDeviceImageDrmFormatModifierInfoEXT.calloc(this)
            .`sType$Default`()
            .drmFormatModifier(modifier)
            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .queueFamilyIndexCount(0)
            .pQueueFamilyIndices(null)
        isTilingSupported(format, usage, EXTImageDrmFormatModifier.VK_IMAGE_TILING_DRM_FORMAT_MODIFIER_EXT, modifierInfo.address())
    }

    private fun isTilingSupported(format: Int, usage: Int, tiling: Int): Boolean = memStack {
        isTilingSupported(format, usage, tiling, 0L)
    }

    private fun isTilingSupported(format: Int, usage: Int, tiling: Int, pNext: Long): Boolean = memStack {
        val formatInfo = VkPhysicalDeviceImageFormatInfo2.calloc(this)
            .`sType$Default`()
            .format(format)
            .type(VK_IMAGE_TYPE_2D)
            .tiling(tiling)
            .usage(usage)
            .pNext(pNext)
        val properties = VkImageFormatProperties2.calloc(this).`sType$Default`()
        vkGetPhysicalDeviceImageFormatProperties2(physicalDevice, formatInfo, properties) == VK_SUCCESS
    }

    override fun close() {
        if (closed) return
        closed = true
        if (lazyDevice.isInitialized()) {
            // Destroying images or the device while queue work still references
            // them is undefined behavior; wait for any remaining work. Harmless
            // no-op when the device is already idle (e.g. after renderer.close).
            vkDeviceWaitIdle(lazyDevice.value).checkAsVkError("wait for device idle on $name")
        }
        vram.close()
        if (lazyDevice.isInitialized()) {
            // Device-bound resources must go before the device itself: the shape
            // pipeline (modules/layout/pipelines) then the logical device. The
            // pipeline is only ever created once the device exists, so a lazy
            // (never-drawn) GPU has nothing to tear down here.
            synchronized(shapePipelineLock) {
                shapePipeline?.close()
                shapePipeline = null
            }
            logger.debug { "Destroying logical device for $name" }
            vkDestroyDevice(lazyDevice.value, null)
        }
    }

    private fun queryLogicalDevice(): VkDevice {
        return memStack {
            val queueCreateInfos = VkDeviceQueueCreateInfo.calloc(1, this)
            queueCreateInfos.get(0)
                .`sType$Default`()
                .queueFamilyIndex(queueFamilyIndex)
                .pQueuePriorities(floats(1f))
            // Dynamic rendering (used by the shape pipeline) is a core Vulkan 1.3
            // feature that must be opted into at device creation; the instance is
            // created against 1.3, and non-1.3 devices fail in the pipeline with a
            // clear error instead of relying on unspecified behavior.
            val vulkan13Features = if (supportsVulkan13) {
                VkPhysicalDeviceVulkan13Features.calloc(this)
                    .`sType$Default`()
                    .dynamicRendering(true)
            } else null
            val deviceCreateInfo = VkDeviceCreateInfo.calloc(this)
                .`sType$Default`()
                .pQueueCreateInfos(queueCreateInfos)
                .ppEnabledExtensionNames(CStr.array(this, requestedExtensions))
                .pNext(vulkan13Features?.address() ?: 0L)
            val handle = outPointer { buf ->
                vkCreateDevice(physicalDevice, deviceCreateInfo, null, buf)
                    .checkAsVkError("create logical device for $name")
            }
            logger.debug { "Created logical device for $name" }
            VkDevice(handle, physicalDevice, deviceCreateInfo)
        }
    }
}
