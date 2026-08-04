package online.coffeeispower.crema.renderers.vulkan

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.lwjgl.*
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT
import org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
import org.lwjgl.vulkan.VK10.*
import java.lang.AutoCloseable


val logger = KotlinLogging.logger {}
private val supportedValidationLayers by lazy {
    memStack {
        val validationLayersCount = outInt { vkEnumerateInstanceLayerProperties(it, null).checkAsVkError("get validationLayers count") }
        logger.debug { "$validationLayersCount validation layers are supported in this environment" }
        val propsBuf = VkLayerProperties.calloc(validationLayersCount, this)
        vkEnumerateInstanceLayerProperties(ints(validationLayersCount), propsBuf).checkAsVkError("get validation layer properties");
        List(validationLayersCount) {
            val layerName = propsBuf.get(it).layerNameString()
            logger.trace { "Validation layer $layerName is supported" }
            layerName
        }
    }
}

private val availableVulkanExtensions by lazy {
    memStack {
        val numExtensions =
            outInt { vkEnumerateInstanceExtensionProperties(null as String?, it, null).checkAsVkError("get vulkan extensions properties") }
        logger.trace { "$numExtensions vulkan extensions are supported in this environment" }
        val availableVulkanExtensionsProps = VkExtensionProperties.calloc(numExtensions, this)
        vkEnumerateInstanceExtensionProperties(
            null as String?,
            intArrayOf(numExtensions),
            availableVulkanExtensionsProps
        ).checkAsVkError("get vulkan extensions properties")
        List(numExtensions) {
            val extensionName = availableVulkanExtensionsProps.get(it).extensionNameString()
            logger.trace { "Vulkan extension $extensionName is supported in this environment" }
            extensionName
        }
    }
}

private const val PORTABILITY_EXTENSION = "VK_KHR_portability_enumeration"

class VulkanInstance(
    enableValidationLayers: Boolean = System.getenv("VULKAN_VALIDATION_LAYERS") == "on",
    enableGLFW: Boolean = false,
) : AutoCloseable {
    val logger = KotlinLogging.logger {}
    val vkInstance: VkInstance
    private val debugUtilsParams: VkDebugUtilsMessengerCreateInfoEXT?
    private val vkDebugHandle: Long
    private val supportsValidation: Boolean
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        logger.debug { "Destroying vulkan instance" }
        if (supportsValidation && vkDebugHandle != 0L) {
            vkDestroyDebugUtilsMessengerEXT(vkInstance, vkDebugHandle, null)
        }
        vkDestroyInstance(vkInstance, null)
        if (supportsValidation) {
            debugUtilsParams?.free()
        }
    }

    init {
        memStack {
            val appInfo = VkApplicationInfo.calloc(this)
                .`sType$Default`()
                .pApplicationName(UTF8("crema"))
                .applicationVersion(1)
                .pEngineName(UTF8("crema vulkan renderer"))
                .engineVersion(0)
                .apiVersion(VK13.VK_API_VERSION_1_3)
            val layers = supportedValidationLayers.filter { it == "VK_LAYER_KHRONOS_validation" };
            val layersCStrings = CStr.array(this, layers)
            val os = System.getProperty("os.name", "generic").lowercase()

            val usePortability =
                availableVulkanExtensions.contains(PORTABILITY_EXTENSION) && ("mac" in os || "darwin" in os)
            val glfwExtensions =
                (if (enableGLFW) GLFWVulkan.glfwGetRequiredInstanceExtensions() else null)?.let { CStr.strings(it) }
                    ?: listOf()
            supportsValidation = enableValidationLayers && layers.isNotEmpty() && EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME in availableVulkanExtensions
            val extensions = buildList {
                addAll(glfwExtensions)
                if (usePortability) add(PORTABILITY_EXTENSION).also {
                    logger.debug { "Requesting portability extension for MacOS support" }
                }
                if (supportsValidation) add(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME).also {
                    logger.debug { "Requesting validation layers" }
                }
                add("VK_KHR_get_physical_device_properties2")
            }
            val extensionsCStrs = CStr.array(this, extensions)
            debugUtilsParams = if (supportsValidation) VulkanValidationLayers.createDebugCallback() else null
            val instanceInfo = VkInstanceCreateInfo.calloc(this)
                .`sType$Default`()
                .pNext(debugUtilsParams?.address() ?: MemoryUtil.NULL)
                .pApplicationInfo(appInfo)
                .ppEnabledLayerNames(if(supportsValidation) layersCStrings else null)
                .ppEnabledExtensionNames(extensionsCStrs)
            if (usePortability) instanceInfo.flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
            vkInstance = outPointer { buf ->
                vkCreateInstance(
                    instanceInfo,
                    null,
                    buf
                ).checkAsVkError("create vulkan instance")
            }.let { VkInstance(it, instanceInfo) }
            try {
                vkDebugHandle = if (supportsValidation && debugUtilsParams != null) {
                    outLong { buf ->
                        EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(
                            vkInstance,
                            debugUtilsParams,
                            null,
                            buf
                        ).checkAsVkError("create debug utils")
                    }
                } else VK_NULL_HANDLE
            } catch (e: VulkanErrorException) {
                close()
                throw e
            }

        }
    }
}


