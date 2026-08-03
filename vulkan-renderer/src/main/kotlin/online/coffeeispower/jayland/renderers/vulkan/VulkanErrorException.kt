package online.coffeeispower.jayland.renderers.vulkan

import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VK11

/**
 * Thrown when a Vulkan call returns an error `VkResult`.
 *
 * A `VkResult` is an error if and only if it is negative; every error code is
 * `VK_ERROR_*` (negative) while all success codes (`VK_SUCCESS`, `VK_TIMEOUT`,
 * `VK_SUBOPTIMAL_KHR`, ...) are zero or positive.
 */
class VulkanErrorException(
    val result: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(buildMessage(message, result), cause) {

    companion object {
        /**
         * Throws [VulkanErrorException] if [result] is a `VkResult` error, doing
         * nothing (and returning normally) otherwise.
         *
         * @param result the `VkResult` returned by the Vulkan call
         * @param message what the call was trying to do, e.g.
         *   `"create vulkan instance"`
         */
        fun fromResult(result: Int, message: String) {
            if (result < 0) throw VulkanErrorException(result, message)
        }
    }
}

private fun buildMessage(message: String, result: Int): String =
    "Failed to $message (${vkResultName(result) ?: "VkResult $result"})"

private fun vkResultName(result: Int): String? = when (result) {
    VK10.VK_SUCCESS -> "VK_SUCCESS"
    VK10.VK_NOT_READY -> "VK_NOT_READY"
    VK10.VK_TIMEOUT -> "VK_TIMEOUT"
    VK10.VK_EVENT_SET -> "VK_EVENT_SET"
    VK10.VK_EVENT_RESET -> "VK_EVENT_RESET"
    VK10.VK_INCOMPLETE -> "VK_INCOMPLETE"
    VK10.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY"
    VK10.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY"
    VK10.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED"
    VK10.VK_ERROR_DEVICE_LOST -> "VK_ERROR_DEVICE_LOST"
    VK10.VK_ERROR_MEMORY_MAP_FAILED -> "VK_ERROR_MEMORY_MAP_FAILED"
    VK10.VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT"
    VK10.VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT"
    VK10.VK_ERROR_FEATURE_NOT_PRESENT -> "VK_ERROR_FEATURE_NOT_PRESENT"
    VK10.VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER"
    VK10.VK_ERROR_TOO_MANY_OBJECTS -> "VK_ERROR_TOO_MANY_OBJECTS"
    VK10.VK_ERROR_FORMAT_NOT_SUPPORTED -> "VK_ERROR_FORMAT_NOT_SUPPORTED"
    VK10.VK_ERROR_FRAGMENTED_POOL -> "VK_ERROR_FRAGMENTED_POOL"
    VK10.VK_ERROR_UNKNOWN -> "VK_ERROR_UNKNOWN"
    VK10.VK_ERROR_VALIDATION_FAILED -> "VK_ERROR_VALIDATION_FAILED"
    VK11.VK_ERROR_OUT_OF_POOL_MEMORY -> "VK_ERROR_OUT_OF_POOL_MEMORY"
    VK11.VK_ERROR_INVALID_EXTERNAL_HANDLE -> "VK_ERROR_INVALID_EXTERNAL_HANDLE"
    else -> null
}

fun Int.checkAsVkError(message: String) = VulkanErrorException.fromResult(this, message)
