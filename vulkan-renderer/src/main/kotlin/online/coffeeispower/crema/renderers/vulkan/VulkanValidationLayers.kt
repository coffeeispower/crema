package online.coffeeispower.crema.renderers.vulkan

import io.github.oshai.kotlinlogging.KotlinLogging
import org.lwjgl.vulkan.EXTDebugUtils
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT
import org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackEXT
import org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT

object VulkanValidationLayers {
    val logger = KotlinLogging.logger {}

    /**
     * Held for the lifetime of the process so the native callback keeps a valid
     * function pointer. Without this reference the JVM may collect the callback,
     * leaving `VkDebugUtilsMessengerCreateInfoEXT.pfnUserCallback` dangling.
     */
    private val debugCallback by lazy {
        VkDebugUtilsMessengerCallbackEXT.create { messageSeverity, _, pCallbackData, _ ->
            val message = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData).pMessageString()
            when {
                messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT != 0 ->
                    logger.error { message }

                messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT != 0 ->
                    logger.warn { message }

                messageSeverity and EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT != 0 ->
                    logger.info { message }

                else -> logger.trace { message }
            }
            VK10.VK_FALSE
        }
    }

    /**
     * Creates a [org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT] that routes Vulkan debug
     * messages into kotlin-logging. The caller owns the returned struct and must
     * free it (e.g. `MemoryUtil.memFree`) once the messenger is created.
     */
    fun createDebugCallback(): VkDebugUtilsMessengerCreateInfoEXT =
        VkDebugUtilsMessengerCreateInfoEXT.calloc()
            .sType(EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT)
            .messageSeverity(
                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_VERBOSE_BIT_EXT or
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT or
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT or
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT
            )
            .messageType(
                EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT or
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT or
                        EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT
            )
            .pfnUserCallback(debugCallback)
}