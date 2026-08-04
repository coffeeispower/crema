import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.core.graphics.Color
import online.coffeeispower.crema.core.platform.linux.DrmScanoutBuffer
import online.coffeeispower.crema.drm.sys.DrmFormats
import online.coffeeispower.crema.drm.sys.Xf86Drm
import online.coffeeispower.crema.renderers.vulkan.VulkanRenderer
import online.coffeeispower.crema.utils.fds.Posix
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.coroutines.runBlocking
import online.coffeeispower.crema.core.graphics.ColorMode
import online.coffeeispower.crema.core.graphics.presentation.Swapchain
import java.lang.foreign.Arena
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class AddFbProbeTest {
    val logger = KotlinLogging.logger {}

    private fun tryAddFb(fd: Int, handle: Int, width: Int, height: Int, stride: Int, modifier: Long, flags: Int): Long {
        Arena.ofConfined().use { arena ->
            val handles = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val pitches = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val offsets = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val modifiers = arena.allocate(ValueLayout.JAVA_LONG, 4L)
            val outId = arena.allocate(ValueLayout.JAVA_INT)
            handles.set(ValueLayout.JAVA_INT, 0L, handle)
            pitches.set(ValueLayout.JAVA_INT, 0L, stride)
            offsets.set(ValueLayout.JAVA_INT, 0L, 0)
            modifiers.set(ValueLayout.JAVA_LONG, 0L, modifier)
            val ret = Xf86Drm.drmModeAddFB2WithModifiers(
                fd, width, height, DrmFormats.XRGB8888,
                handles, pitches, offsets, modifiers, outId, flags,
            )
            if (ret == 0) return outId.get(ValueLayout.JAVA_INT, 0).toLong()
            return -ret.toLong()
        }
    }

    private fun firstCard(): Path? {
        for (i in 0 until 64) {
            val path = Path.of("/dev/dri/card$i")
            if (Files.exists(path)) return path
        }
        return null
    }

    @Test
    fun probe() {
        val card = firstCard() ?: run { assumeTrue(false, "no /dev/dri/cardN"); return }
        val fd = Posix.open(card.toString(), 2) // O_RDWR
        assumeTrue(fd >= 0, "cannot open $card: ${fd}")
        try {
            VulkanRenderer().use { renderer ->
                val vram = renderer.deviceManager.gpus[0].vram
                val sizes = listOf(64 to 64, 1920 to 1200, 640 to 400)
                for ((w, h) in sizes) {
                    val buffer = vram.allocateBufferForScanout(w, h) as DrmScanoutBuffer
                    val submission = renderer.beginFrame(buffer) { clear(Color.RED) }
                    val fence = submission.exportInFenceFd()
                    runBlocking {
                        val d = online.coffeeispower.crema.utils.fds.PollDispatcher("p")
                        Posix.setNonBlocking(fence)
                        d.watch(fence).use { it.awaitReadable() }
                        d.close()
                    }
                    Posix.close(fence)
                    submission.close()
                    val dmaBuf = buffer.exportDmaBufFd()
                    val handle = Arena.ofConfined().use { a ->
                        val p = a.allocate(ValueLayout.JAVA_INT)
                        val ret = Xf86Drm.drmPrimeFDToHandle(fd, dmaBuf, p)
                        if (ret != 0) { logger.info { "drmPrimeFDToHandle failed: $ret" }; return@use -1 }
                        p.get(ValueLayout.JAVA_INT, 0)
                    }
                    val fdinfo = java.nio.file.Files.readString(Path.of("/proc/self/fdinfo/$dmaBuf"))
                    logger.info { "${w}x$h fdinfo: ${fdinfo.trim().replace('\n', ' ')}" }
                    Posix.close(dmaBuf)
                    if (handle < 0) return
                    logger.info { "${w}x$h: format=${buffer.drmFormat} modifier=${buffer.drmModifier} usesExplicit=${buffer.usesExplicitModifier} stride=${buffer.stride} expectedPitch=${w * 4} handle=$handle" }
                    val fbImplicit = tryAddFb(fd, handle, buffer.width, buffer.height, buffer.stride, buffer.drmModifier, 0)
                    logger.info { "${w}x$h addfb flags=0 (implicit): ${if (fbImplicit >= 0) "OK id=$fbImplicit" else "FAILED -${fbImplicit}"}" }
                    if (fbImplicit >= 0) Xf86Drm.drmModeRmFB(fd, fbImplicit.toInt())
                    val fbMod = tryAddFb(fd, handle, buffer.width, buffer.height, buffer.stride, buffer.drmModifier, Xf86Drm.DRM_MODE_FB_MODIFIERS())
                    logger.info { "${w}x$h addfb flags=DRM_MODE_FB_MODIFIERS modifier=${buffer.drmModifier}: ${if (fbMod >= 0) "OK id=$fbMod" else "FAILED -${fbMod}"}" }
                    if (fbMod >= 0) Xf86Drm.drmModeRmFB(fd, fbMod.toInt())
                    val fbWrong = tryAddFb(fd, handle, buffer.width, buffer.height, buffer.stride, 4L, Xf86Drm.DRM_MODE_FB_MODIFIERS())
                    logger.info { "${w}x$h addfb flags=MODIFIERS modifier=4 (mismatch): ${if (fbWrong >= 0) "OK id=$fbWrong" else "FAILED -${fbWrong}"}" }
                    if (fbWrong >= 0) Xf86Drm.drmModeRmFB(fd, fbWrong.toInt())
                    buffer.close()
                }

                logger.info { "=== reproducing app flow: client caps + 2-deep swapchain ===" }
                val capsRet = Xf86Drm.drmSetClientCap(fd, Xf86Drm.DRM_CLIENT_CAP_ATOMIC().toLong(), 1L)
                val capsRet2 = Xf86Drm.drmSetClientCap(fd, Xf86Drm.DRM_CLIENT_CAP_UNIVERSAL_PLANES().toLong(), 1L)
                logger.info { "drmSetClientCap ATOMIC=$capsRet UNIVERSAL_PLANES=$capsRet2" }
                val swapchain = Swapchain(
                    1920,
                    1200,
                    ColorMode.RGBA8,
                    depth = 2,
                    vram,
                    allowedModifiers = listOf(0L)
                ) // DRM_FORMAT_MOD_LINEAR
                val bufs = runBlocking {
                    listOf(swapchain.acquireBuffer(), swapchain.acquireBuffer())
                }
                for (b in bufs) {
                    val db = b as DrmScanoutBuffer
                    val dmaBuf2 = db.exportDmaBufFd()
                    val h = Arena.ofConfined().use { a ->
                        val p = a.allocate(ValueLayout.JAVA_INT)
                        val ret = Xf86Drm.drmPrimeFDToHandle(fd, dmaBuf2, p)
                        Posix.close(dmaBuf2)
                        if (ret != 0) { logger.info { "primeFDToHandle failed: $ret" }; return@use -1 }
                        p.get(ValueLayout.JAVA_INT, 0)
                    }
                    if (h < 0) return
                    for (flags in listOf(0 to "implicit", Xf86Drm.DRM_MODE_FB_MODIFIERS() to "modifiers")) {
                        val fb = tryAddFb(fd, h, db.width, db.height, db.stride, db.drmModifier, flags.first)
                        logger.info { "swapchain buf ${db.width}x${db.height} stride=${db.stride} mod=${db.drmModifier} flags=${flags.second}: ${if (fb >= 0) "OK id=$fb" else "FAILED -${fb}"}" }
                        if (fb >= 0) Xf86Drm.drmModeRmFB(fd, fb.toInt())
                    }
                }
                bufs.forEach { swapchain.release(it) }
                swapchain.close()
            }
        } finally {
            Posix.close(fd)
        }
    }
}
