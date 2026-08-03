import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import online.coffeeispower.jayland.core.Color
import online.coffeeispower.jayland.core.platform.linux.DrmScanoutBuffer
import online.coffeeispower.jayland.renderers.vulkan.VulkanDeviceManager
import online.coffeeispower.jayland.renderers.vulkan.VulkanInstance
import online.coffeeispower.jayland.renderers.vulkan.VulkanRenderer
import online.coffeeispower.jayland.utils.fds.PollDispatcher
import online.coffeeispower.jayland.utils.fds.Posix
import kotlin.test.Test

class VulkanTest {
    val logger = KotlinLogging.logger {  };
    @Test
    fun createInstance() {
        VulkanInstance(enableValidationLayers = true, enableGLFW = false).close()
    }
    @Test
    fun probeGPUs() {
        VulkanInstance(enableValidationLayers = true, enableGLFW = false).use {
            VulkanDeviceManager(it).use { manager ->
                for (gpu in manager.gpus) {
                    logger.info { "→ ${gpu.name}" }
                }
            }
        }
    }
    @Test
    fun allocateBuffer() {
        VulkanInstance(enableValidationLayers = true, enableGLFW = false).use {
            VulkanDeviceManager(it).use { manager ->
                manager.gpus[0].vram.allocateBufferForScanout(1920, 1080).close()
            }
        }
    }

    @Test
    fun submitAndAwaitSignal() {
        VulkanRenderer().use { renderer ->
            val gpu = renderer.deviceManager.gpus[0]
            val buffer = gpu.vram.allocateBufferForScanout(64, 64)
            val submission = renderer.beginFrame(buffer) {
                clear(Color.RED)
            }
            runBlocking { submission.signal.awaitSignaled() }
            submission.close()
            buffer.close()
        }
    }

    @Test
    fun exportDmaBuf() {
        VulkanRenderer().use { renderer ->
            val gpu = renderer.deviceManager.gpus[0]
            val buffer = gpu.vram.allocateBufferForScanout(64, 64) as DrmScanoutBuffer
            val fd = buffer.exportDmaBufFd()
            check(fd >= 0) { "dma-buf export returned $fd" }
            Posix.close(fd)
            buffer.close()
        }
    }

    @Test
    fun exportInFenceFd() {
        VulkanRenderer().use { renderer ->
            val gpu = renderer.deviceManager.gpus[0]
            val buffer = gpu.vram.allocateBufferForScanout(64, 64)
            val submission = renderer.beginFrame(buffer) {
                clear(Color.RED)
            }
            val inFenceFd = submission.exportInFenceFd()
            runBlocking {
                PollDispatcher("test-fence").use { dispatcher ->
                    Posix.setNonBlocking(inFenceFd)
                    dispatcher.watch(inFenceFd).use { it.awaitReadable() }
                }
            }
            Posix.close(inFenceFd)
            submission.close()
            buffer.close()
        }
    }

    @Test
    fun scanoutBufferContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val gpu = renderer.deviceManager.gpus[0]
            val buffer = gpu.vram.allocateBufferForScanout(64, 64) as DrmScanoutBuffer
            val submission = renderer.beginFrame(buffer) {
                clear(Color.RED)
            }
            val inFenceFd = submission.exportInFenceFd()
            runBlocking {
                PollDispatcher("test-fence").use { dispatcher ->
                    Posix.setNonBlocking(inFenceFd)
                    dispatcher.watch(inFenceFd).use { it.awaitReadable() }
                }
            }
            Posix.close(inFenceFd)
            submission.close()
            if (buffer.drmModifier == 1L) {
                val dmaBufFd = buffer.exportDmaBufFd()
                val bytes = Posix.mmapRead(dmaBufFd, buffer.stride.toLong() * buffer.height)
                Posix.close(dmaBufFd)
                // Scanout buffers are B8G8R8A8: byte order in memory is B, G, R, A.
                val b = bytes[0].toInt() and 0xFF
                val g = bytes[1].toInt() and 0xFF
                val r = bytes[2].toInt() and 0xFF
                val a = bytes[3].toInt() and 0xFF
                check(r == 255 && g == 0 && b == 0 && a == 255) {
                    "expected clear red (255,0,0,255), got ($r,$g,$b,$a)"
                }
            }
            buffer.close()
        }
    }
}