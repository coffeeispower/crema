package online.coffeeispower.jayland.renderers.vulkan

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.DeviceManager
import online.coffeeispower.jayland.lwjgl.memStack
import online.coffeeispower.jayland.lwjgl.outInt
import org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices
import org.lwjgl.vulkan.VkPhysicalDevice

/**
 * Device manager that maps every Vulkan physical device to a [VulkanGPU], each
 * of which lazily initializes its own logical device on first use.
 *
 * Must be closed *before* its [VulkanInstance], since logical devices are
 * invalidated once the instance is destroyed.
 */
class VulkanDeviceManager(val instance: VulkanInstance) : DeviceManager {
    val logger = KotlinLogging.logger {}

    private val lazyGpus = lazy {
        memStack {
            val count = outInt { vkEnumeratePhysicalDevices(instance.vkInstance, it, null) }
            val handles = mallocPointer(count)
            vkEnumeratePhysicalDevices(instance.vkInstance, intArrayOf(count), handles).checkAsVkError("enumerate physical devices")
            List(count) { index ->
                VulkanGPU(VkPhysicalDevice(handles.get(index), instance.vkInstance), instance)
                    .also { gpu -> logger.debug { "Enumerated vulkan GPU ${gpu.name}" } }
            }
        }
    }
    override val gpus: List<VulkanGPU> by lazyGpus

    override fun close() {
        if (lazyGpus.isInitialized()) {
            gpus.forEach { it.close() }
        }
    }
}
