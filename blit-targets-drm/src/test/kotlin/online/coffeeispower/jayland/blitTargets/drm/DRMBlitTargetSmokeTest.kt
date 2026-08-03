package online.coffeeispower.jayland.blitTargets.drm

import online.coffeeispower.jayland.core.ColorMode
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmModeConnector
import online.coffeeispower.jayland.drm.sys._drmModeEncoder
import online.coffeeispower.jayland.drm.sys._drmModePlane
import online.coffeeispower.jayland.drm.sys._drmModePlaneRes
import online.coffeeispower.jayland.drm.sys._drmModeRes
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Smoke tests for the DRM blit target. The pure helpers run everywhere; the
 * integration tests (card path resolution, device open, atomic property
 * resolution) need a real DRM card and DRM master privileges, and are skipped
 * otherwise so CI and root-less developers do not fail on them.
 */
class DRMBlitTargetSmokeTest {

    @Test
    fun fourccConstantsMatchUapi() {
        assertEquals(0x34325258, DrmFormats.XRGB8888)
        assertEquals(0x30335258, DrmFormats.XRGB2101010)
        assertEquals(1L, DrmFormats.MOD_LINEAR)
        assertEquals(0L, DrmFormats.MOD_INVALID)
    }

    @Test
    fun colorModeFourccMapping() {
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA8.drmFourcc)
        assertEquals(DrmFormats.XRGB2101010, ColorMode.RGB10A2.drmFourcc)
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA16F.drmFourcc)
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA32F.drmFourcc)
    }

    @Test
    fun cardPathResolverFindsTheFirstCard() {
        val first = firstCard() ?: run {
            assumeTrue(false, "no /dev/dri/cardN present")
            return
        }
        val resolved = CardPathResolver.resolve(majorOf(first), minorOf(first))
        assertNotNull(resolved, "expected to resolve ${first} to an existing devnode")
        assertTrue(Files.exists(resolved))
        assertTrue(resolved.toString().startsWith("/dev/dri/card"))
    }

    @Test
    fun cardPathResolverReturnsNullForUnknownDev() {        // 255:255 is not a DRM card; resolution must simply report no match.
        assertEquals(null, CardPathResolver.resolve(255, 255))
    }

    @Test
    fun openDeviceAndResolveAtomicProperties() {
        val path = firstCard() ?: run {
            assumeTrue(false, "no /dev/dri/cardN present")
            return
        }
        val device = try {
            DRMDevice.open(path)
        } catch (e: Throwable) {
            assumeTrue(false, "cannot open DRM card $path (need root / DRM master): ${e.message}")
            return
        }
        device.use { dev ->
            val resources = Xf86Drm.drmModeGetResources(dev.fd)
            assumeTrue(resources.address() != 0L, "drmModeGetResources failed")
            try {
                val connectorCount = _drmModeRes.count_connectors(resources)
                val connectors = _drmModeRes.connectors(resources)
                for (i in 0 until connectorCount) {
                    val connectorId = connectors.get(ValueLayout.JAVA_INT, i * 4L)
                    val (crtcId, planeId) = crtcAndPrimaryPlane(dev.fd, connectorId) ?: continue
                    val props = DrmProperties.resolve(dev.fd, planeId, crtcId, connectorId)
                    assertTrue(props.planeCrtcId > 0, "plane CRTC_ID property id")
                    assertTrue(props.planeFbId > 0, "plane FB_ID property id")
                    assertTrue(props.planeInFenceFd > 0, "plane IN_FENCE_FD property id")
                    assertTrue(props.planeType > 0, "plane type property id")
                    assertTrue(props.crtcModeId > 0, "CRTC MODE_ID property id")
                    assertTrue(props.crtcActive > 0, "CRTC ACTIVE property id")
                    assertTrue(props.connectorCrtcId > 0, "connector CRTC_ID property id")
                    return
                }
                assumeTrue(false, "no connected connector with a bound CRTC on $path")
            } finally {
                Xf86Drm.drmModeFreeResources(resources)
            }
        }
    }

    /** Follows a connected connector's encoder to its CRTC, then finds a primary plane usable on it. */
    private fun crtcAndPrimaryPlane(fd: Int, connectorId: Int): Pair<Int, Int>? {
        val connector = Xf86Drm.drmModeGetConnector(fd, connectorId)
        if (connector.address() == 0L) return null
        return try {
            // 1 == DRM_MODE_CONNECTED
            if (_drmModeConnector.connection(connector) != 1) return null
            val encoderId = _drmModeConnector.encoder_id(connector)
            val encoder = Xf86Drm.drmModeGetEncoder(fd, encoderId)
            if (encoder.address() == 0L) return null
            val crtcId = try {
                _drmModeEncoder.crtc_id(encoder)
            } finally {
                Xf86Drm.drmModeFreeEncoder(encoder)
            }
            if (crtcId == 0) return null

            val resources = Xf86Drm.drmModeGetPlaneResources(fd)
            if (resources.address() == 0L) return null
            try {
                val count = _drmModePlaneRes.count_planes(resources)
                val planeIds = _drmModePlaneRes.planes(resources)
                    .reinterpret(count.toLong() * ValueLayout.JAVA_INT.byteSize())
                for (i in 0 until count) {
                    val planeId = planeIds.get(ValueLayout.JAVA_INT, i * 4L)
                    val type = DrmProperties.propertyValue(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "type")
                        ?: continue
                    if (type.toInt() != Xf86Drm.DRM_PLANE_TYPE_PRIMARY()) continue
                    val plane = Xf86Drm.drmModeGetPlane(fd, planeId)
                    if (plane.address() == 0L) continue
                    try {
                        // Any CRTC bit set is enough for the smoke test.
                        if (_drmModePlane.possible_crtcs(plane) != 0) return crtcId to planeId
                    } finally {
                        Xf86Drm.drmModeFreePlane(plane)
                    }
                }
                null
            } finally {
                Xf86Drm.drmModeFreePlaneResources(resources)
            }
        } finally {
            Xf86Drm.drmModeFreeConnector(connector)
        }
    }

    private fun firstCard(): Path? {
        for (i in 0 until 64) {
            val path = Path.of("/dev/dri/card$i")
            if (Files.exists(path)) return path
        }
        return null
    }

    private fun majorOf(path: Path): Int {
        val dev = Files.readString(Path.of("/sys/class/drm/${path.fileName}/dev")).trim()
        return dev.substringBefore(':').toInt()
    }

    private fun minorOf(path: Path): Int {
        val dev = Files.readString(Path.of("/sys/class/drm/${path.fileName}/dev")).trim()
        return dev.substringAfter(':').toInt()
    }
}
