package online.coffeeispower.jayland.blitTargets.drm

import online.coffeeispower.jayland.core.ColorMode
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmModeConnector
import online.coffeeispower.jayland.drm.sys._drmModeEncoder
import online.coffeeispower.jayland.drm.sys._drmModePlane
import online.coffeeispower.jayland.drm.sys._drmModePlaneRes
import online.coffeeispower.jayland.drm.sys._drmModePropertyBlob
import online.coffeeispower.jayland.drm.sys._drmModeRes
import online.coffeeispower.jayland.drm.sys.drm_format_modifier
import online.coffeeispower.jayland.drm.sys.drm_format_modifier_blob
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.foreign.Arena
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
 * resolution) need a real DRM card and DRM master privileges (an empty TTY,
 * not a desktop session), and are skipped otherwise so CI and developers
 * without a free connector do not fail on them.
 */
class DRMBlitTargetSmokeTest {

    @Test
    fun fourccConstantsMatchUapi() {
        assertEquals(0x34325258, DrmFormats.XRGB8888)
        assertEquals(0x30335258, DrmFormats.XRGB2101010)
        assertEquals(0L, DrmFormats.MOD_LINEAR)
        assertEquals(0x00FFFFFFFFFFFFFFL, DrmFormats.MOD_INVALID)
    }

    @Test
    fun atomicAddPropertyReturnsPropertyCount() {
        // drmModeAtomicAddProperty mutates only the request struct (no card,
        // no DRM master needed). It returns the request's property count on
        // success (0 in libdrm <2.4.134, the cursor afterwards) and a negative
        // errno on failure — never 0-or-error. DRMCommitter.addProperty relies
        // on this, so lock the contract down here.
        val request = Xf86Drm.drmModeAtomicAlloc()
        assertTrue(request.address() != 0L, "drmModeAtomicAlloc failed")
        try {
            assertEquals(1, Xf86Drm.drmModeAtomicAddProperty(request, 33, 20, 149L))
            assertEquals(2, Xf86Drm.drmModeAtomicAddProperty(request, 33, 21, 7L))
            assertEquals(3, Xf86Drm.drmModeAtomicAddProperty(request, 149, 12, 1L))
        } finally {
            Xf86Drm.drmModeAtomicFree(request)
        }
    }

    @Test
    fun colorModeFourccMapping() {
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA8.drmFourcc)
        assertEquals(DrmFormats.XRGB2101010, ColorMode.RGB10A2.drmFourcc)
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA16F.drmFourcc)
        assertEquals(DrmFormats.XRGB8888, ColorMode.RGBA32F.drmFourcc)
    }

    @Test
    fun inFormatsBlobModifiersParseWithoutARealCard() {
        // Builds a synthetic IN_FORMATS blob (kernel drm_format_modifier_blob)
        // in memory and checks the parsing that drives swapchain allocation.
        Arena.ofConfined().use { arena ->
            val formats = intArrayOf(DrmFormats.XRGB8888, 0x34325241) // XRGB8888, ARGB8888
            val headerSize = drm_format_modifier_blob.layout().byteSize().toInt()
            val formatsOffset = headerSize
            val modifiersOffset = formatsOffset + formats.size * 4
            val blobSize = modifiersOffset + 24 // one drm_format_modifier entry

            val blobSegment = arena.allocate(blobSize.toLong())
            drm_format_modifier_blob.version(blobSegment, 1)
            drm_format_modifier_blob.count_formats(blobSegment, formats.size)
            drm_format_modifier_blob.formats_offset(blobSegment, formatsOffset)
            drm_format_modifier_blob.count_modifiers(blobSegment, 1)
            drm_format_modifier_blob.modifiers_offset(blobSegment, modifiersOffset)
            formats.forEachIndexed { i, fourcc ->
                blobSegment.set(ValueLayout.JAVA_INT, formatsOffset.toLong() + i * 4L, fourcc)
            }
            // The single modifier applies to format index 0 (XRGB8888): window 0, bit 0.
            val entry = blobSegment.asSlice(modifiersOffset.toLong(), drm_format_modifier.layout().byteSize())
            drm_format_modifier.formats(entry, 1L)
            drm_format_modifier.offset(entry, 0)
            drm_format_modifier.modifier(entry, DrmFormats.MOD_LINEAR)

            val blobStruct = arena.allocate(_drmModePropertyBlob.layout())
            _drmModePropertyBlob.data(blobStruct, blobSegment)
            _drmModePropertyBlob.length(blobStruct, blobSize)

            assertEquals(
                listOf(DrmFormats.MOD_LINEAR),
                DRMConnector.readFormatModifiers(blobStruct, DrmFormats.XRGB8888),
            )
            // ARGB8888 has no matching modifier entry: falls back to LINEAR-only.
            assertEquals(
                listOf(DrmFormats.MOD_LINEAR),
                DRMConnector.readFormatModifiers(blobStruct, 0x34325241),
            )
        }
    }

    @Test
    fun truncatedInFormatsBlobFallsBackToLinear() {
        Arena.ofConfined().use { arena ->
            // A header claiming tables that extend past the blob's real length
            // must not throw: enable should fall back to LINEAR.
            val headerSize = drm_format_modifier_blob.layout().byteSize().toInt()
            val blobSegment = arena.allocate(headerSize.toLong())
            drm_format_modifier_blob.count_formats(blobSegment, 100)
            drm_format_modifier_blob.formats_offset(blobSegment, headerSize)
            drm_format_modifier_blob.count_modifiers(blobSegment, 100)
            drm_format_modifier_blob.modifiers_offset(blobSegment, headerSize + 400)

            val blobStruct = arena.allocate(_drmModePropertyBlob.layout())
            _drmModePropertyBlob.data(blobStruct, blobSegment)
            _drmModePropertyBlob.length(blobStruct, headerSize)

            assertEquals(
                listOf(DrmFormats.MOD_LINEAR),
                DRMConnector.readFormatModifiers(blobStruct, DrmFormats.XRGB8888),
            )
        }
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
            assumeTrue(false, "cannot open DRM card $path (need a free TTY / DRM master): ${e.message}")
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
