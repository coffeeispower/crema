import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import online.coffeeispower.crema.core.graphics.Border
import online.coffeeispower.crema.core.graphics.BorderMode
import online.coffeeispower.crema.core.graphics.Color
import online.coffeeispower.crema.core.graphics.Rectangle
import online.coffeeispower.crema.core.graphics.RoundedRectangle
import online.coffeeispower.crema.core.platform.linux.DrmScanoutImageBuffer
import online.coffeeispower.crema.drm.sys.DrmFormats
import online.coffeeispower.crema.renderers.vulkan.VulkanDeviceManager
import online.coffeeispower.crema.renderers.vulkan.VulkanInstance
import online.coffeeispower.crema.renderers.vulkan.VulkanRenderer
import online.coffeeispower.crema.utils.fds.PollDispatcher
import online.coffeeispower.crema.utils.fds.Posix
import kotlin.test.Test

class VulkanTest {
    val logger = KotlinLogging.logger {  };

    /** Allocates a LINEAR scanout buffer so its contents can be read back via mmap. */
    private fun linearBuffer(renderer: VulkanRenderer): DrmScanoutImageBuffer =
        renderer.deviceManager.gpus[0].vram
            .allocateBufferForScanout(64, 64, allowedModifiers = listOf(DrmFormats.MOD_LINEAR))
            as DrmScanoutImageBuffer
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
            runBlocking { submission.latch.await() }
            submission.close()
            buffer.close()
        }
    }

    @Test
    fun exportDmaBuf() {
        VulkanRenderer().use { renderer ->
            val gpu = renderer.deviceManager.gpus[0]
            val buffer = gpu.vram.allocateBufferForScanout(64, 64) as DrmScanoutImageBuffer
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
            val buffer = linearBuffer(renderer)
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
            buffer.close()
        }
    }

    @Test
    fun drawRectContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val submission = renderer.beginFrame(buffer) {
                clear(Color(0f, 0f, 0f))
                drawRect(Rectangle(16f, 16f, 32f, 32f), fillColor = Color.RED)
            }
            awaitSignal(submission)
            submission.close()
            val center = pixel(buffer, 32, 32)
            check(center.contentEquals(intArrayOf(0, 0, 255, 255))) { "center pixel is ${center.contentToString()}" }
            val corner = pixel(buffer, 2, 2)
            check(corner.contentEquals(intArrayOf(0, 0, 0, 255))) { "corner pixel is ${corner.contentToString()}" }
            buffer.close()
        }
    }

    @Test
    fun drawRoundedRectContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val submission = renderer.beginFrame(buffer) {
                clear(Color(0f, 0f, 0f))
                drawRect(RoundedRectangle(Rectangle(16f, 16f, 32f, 32f), radius = 12f), fillColor = Color.RED)
            }
            awaitSignal(submission)
            submission.close()
            val center = pixel(buffer, 32, 32)
            check(center.contentEquals(intArrayOf(0, 0, 255, 255))) { "center pixel is ${center.contentToString()}" }
            // Top-left corner (18,18) is outside the 12px corner circle.
            val cutCorner = pixel(buffer, 18, 18)
            check(cutCorner.contentEquals(intArrayOf(0, 0, 0, 255))) { "corner pixel is ${cutCorner.contentToString()}" }
            // Top edge, away from the corners, is fully covered.
            val edge = pixel(buffer, 32, 18)
            check(edge.contentEquals(intArrayOf(0, 0, 255, 255))) { "edge pixel is ${edge.contentToString()}" }
            buffer.close()
        }
    }

    @Test
    fun drawBorderOutsideContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val green = Color(0f, 1f, 0f)
            val submission = renderer.beginFrame(buffer) {
                clear(Color(0f, 0f, 0f))
                drawRectBorder(
                    Rectangle(16f, 16f, 32f, 32f),
                    Border(green, borderWidth = 4f, borderMode = BorderMode.Outside),
                )
            }
            awaitSignal(submission)
            submission.close()
            // 4px outside the rect edge (x=12) lies in the border band.
            val border = pixel(buffer, 12, 32)
            check(border.contentEquals(intArrayOf(0, 255, 0, 255))) { "border pixel is ${border.contentToString()}" }
            // The rect interior is untouched: the border only paints outside.
            val interior = pixel(buffer, 32, 32)
            check(interior.contentEquals(intArrayOf(0, 0, 0, 255))) { "interior pixel is ${interior.contentToString()}" }
            buffer.close()
        }
    }

    @Test
    fun drawBorderMiddleContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val green = Color(0f, 1f, 0f)
            val submission = renderer.beginFrame(buffer) {
                clear(Color(0f, 0f, 0f))
                drawRectBorder(
                    Rectangle(16f, 16f, 32f, 32f),
                    Border(green, borderWidth = 4f, borderMode = BorderMode.Middle),
                )
            }
            awaitSignal(submission)
            submission.close()
            // A middle border straddles the edge: 2px outside (x=14) and 2px inside (x=16).
            val outside = pixel(buffer, 14, 32)
            check(outside.contentEquals(intArrayOf(0, 255, 0, 255))) { "outside pixel is ${outside.contentToString()}" }
            val inside = pixel(buffer, 16, 32)
            check(inside.contentEquals(intArrayOf(0, 255, 0, 255))) { "inside pixel is ${inside.contentToString()}" }
            buffer.close()
        }
    }

    @Test
    fun drawBorderInsideContentRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val green = Color(0f, 1f, 0f)
            val submission = renderer.beginFrame(buffer) {
                clear(Color(0f, 0f, 0f))
                drawRectBorder(
                    Rectangle(16f, 16f, 32f, 32f),
                    Border(green, borderWidth = 4f, borderMode = BorderMode.Inside),
                )
            }
            awaitSignal(submission)
            submission.close()
            // 2px inside the rect edge (x=17) lies in the border band.
            val border = pixel(buffer, 17, 32)
            check(border.contentEquals(intArrayOf(0, 255, 0, 255))) { "border pixel is ${border.contentToString()}" }
            // The interior beyond the border and its AA ramp is untouched.
            val interior = pixel(buffer, 22, 32)
            check(interior.contentEquals(intArrayOf(0, 0, 0, 255))) { "interior pixel is ${interior.contentToString()}" }
            buffer.close()
        }
    }

    @Test
    fun drawRectFillWithBorderRoundTrip() {
        VulkanRenderer().use { renderer ->
            val buffer = linearBuffer(renderer)
            val submission = renderer.beginFrame(buffer) {
                clear(Color.RED)
                drawRect(
                    Rectangle(16f, 16f, 32f, 32f),
                    fillColor = Color.WHITE,
                    border = Border(Color.GREEN, borderWidth = 4f, borderMode = BorderMode.Outside),
                )
            }
            awaitSignal(submission)
            submission.close()
            // The outside border band shows solidly right up to the fill (no
            // half-transparent AA line between them).
            val border = pixel(buffer, 12, 32)
            check(border.contentEquals(intArrayOf(0, 128, 0, 255))) { "border pixel is ${border.contentToString()}" }
            val boundary = pixel(buffer, 15, 32)
            check(boundary.contentEquals(intArrayOf(0, 128, 0, 255))) { "boundary pixel is ${boundary.contentToString()}" }
            // The fill covers the rect interior.
            val fill = pixel(buffer, 17, 32)
            check(fill.contentEquals(intArrayOf(255, 255, 255, 255))) { "fill pixel is ${fill.contentToString()}" }
            buffer.close()
        }
    }

    /** Blocks until the submission's fence signal fires, then consumes it. */
    private fun awaitSignal(submission: online.coffeeispower.crema.core.graphics.gpu.Submission) {
        val inFenceFd = submission.exportInFenceFd()
        runBlocking {
            PollDispatcher("test-fence").use { dispatcher ->
                Posix.setNonBlocking(inFenceFd)
                dispatcher.watch(inFenceFd).use { it.awaitReadable() }
            }
        }
        Posix.close(inFenceFd)
    }

    /** Reads one B8G8R8A8 pixel from a linear scanout buffer. */
    private fun pixel(buffer: DrmScanoutImageBuffer, x: Int, y: Int): IntArray {
        val dmaBufFd = buffer.exportDmaBufFd()
        val bytes = Posix.mmapRead(dmaBufFd, buffer.stride.toLong() * buffer.height)
        Posix.close(dmaBufFd)
        val offset = y * buffer.stride + x * 4
        return intArrayOf(
            bytes[offset].toInt() and 0xFF,
            bytes[offset + 1].toInt() and 0xFF,
            bytes[offset + 2].toInt() and 0xFF,
            bytes[offset + 3].toInt() and 0xFF,
        )
    }
}