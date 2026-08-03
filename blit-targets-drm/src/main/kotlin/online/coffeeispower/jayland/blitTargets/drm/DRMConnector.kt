package online.coffeeispower.jayland.blitTargets.drm

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.ColorMode
import online.coffeeispower.jayland.core.Connector
import online.coffeeispower.jayland.core.GPU
import online.coffeeispower.jayland.core.Mode
import online.coffeeispower.jayland.core.Monitor
import online.coffeeispower.jayland.core.Output
import online.coffeeispower.jayland.core.Swapchain
import online.coffeeispower.jayland.core.VRam
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmModeConnector
import online.coffeeispower.jayland.drm.sys._drmModeModeInfo
import online.coffeeispower.jayland.drm.sys._drmModePlane
import online.coffeeispower.jayland.drm.sys._drmModePlaneRes
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A connected DRM connector (a physical port). [enable] wires up the swapchain
 * and the plane/CRTC pair it scans out through, returning a [DRMOutput].
 */
class DRMConnector(
    override val enabled: Boolean,
    override val monitor: Monitor,
    override val preferredMode: Mode,
    private val device: DRMDevice,
    private val connectorId: Int,
    private val crtcId: Int,
    private val crtcIndex: Int,
    private val eventLoop: DRMEventLoop,
    override val gpu: GPU,
) : Connector {

    private val logger = KotlinLogging.logger {}

    override fun enable(mode: Mode, vram: VRam): Output {
        val colorMode = ColorMode.RGBA8
        val swapchain = Swapchain(mode.width, mode.height, colorMode, depth = 2, vram)
        val planeId = findPrimaryPlane(colorMode.drmFourcc)
        val props = DrmProperties.resolve(device.fd, planeId, crtcId, connectorId)
        val modeBlobId = createModeBlob(mode)
        logger.info {
            "Enabled ${monitor.name} on CRTC $crtcId/plane $planeId at ${mode.width}x${mode.height}@${mode.refreshRateHz}Hz"
        }
        return DRMOutput(
            monitor = monitor,
            mode = mode,
            swapchain = swapchain,
            device = device,
            crtcId = crtcId,
            planeId = planeId,
            connectorId = connectorId,
            props = props,
            modeBlobId = modeBlobId,
            eventLoop = eventLoop,
        )
    }

    override fun close() = Unit

    /**
     * Finds the PRIMARY plane that can scan out [fourcc] on this connector's
     * CRTC. The plane must have `DRM_PLANE_TYPE_PRIMARY`, accept the buffer
     * fourcc, and be usable on our CRTC index.
     */
    private fun findPrimaryPlane(fourcc: Int): Int {
        val resources = Xf86Drm.drmModeGetPlaneResources(device.fd)
        if (resources.address() == 0L) error("drmModeGetPlaneResources failed")
        try {
            Arena.ofConfined().use { arena ->
                val count = _drmModePlaneRes.count_planes(resources)
                val planeIds = _drmModePlaneRes.planes(resources)
                    .reinterpret(count.toLong() * ValueLayout.JAVA_INT.byteSize(), arena, null)
                for (i in 0 until count) {
                    val planeId = planeIds.get(ValueLayout.JAVA_INT, i * 4L)
                    val type = DrmProperties.propertyValue(
                        device.fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "type",
                    ) ?: continue
                    if (type.toInt() != Xf86Drm.DRM_PLANE_TYPE_PRIMARY()) continue

                    val plane = Xf86Drm.drmModeGetPlane(device.fd, planeId)
                    if (plane.address() == 0L) continue
                    try {
                        if ((_drmModePlane.possible_crtcs(plane) shr crtcIndex) and 1 == 0) continue
                        val formatCount = _drmModePlane.count_formats(plane)
                        val formats = _drmModePlane.formats(plane)
                            .reinterpret(formatCount.toLong() * ValueLayout.JAVA_INT.byteSize(), arena, null)
                        val supported = (0 until formatCount).any {
                            formats.get(ValueLayout.JAVA_INT, it * 4L) == fourcc
                        }
                        if (supported) return planeId
                    } finally {
                        Xf86Drm.drmModeFreePlane(plane)
                    }
                }
            }
        } finally {
            Xf86Drm.drmModeFreePlaneResources(resources)
        }
        error("No primary plane for fourcc 0x${fourcc.toString(16)} on CRTC $crtcId")
    }

    /** Copies the requested [mode]'s `drm_mode_modeinfo` into a MODE_ID blob. */
    private fun createModeBlob(mode: Mode): Int {
        val connector = Xf86Drm.drmModeGetConnector(device.fd, connectorId)
        if (connector.address() == 0L) error("drmModeGetConnector($connectorId) failed")
        return try {
            Arena.ofConfined().use { arena ->
                val modeCount = _drmModeConnector.count_modes(connector)
                val modes = _drmModeConnector.modes(connector)
                    .reinterpret(modeCount.toLong() * _drmModeModeInfo.layout().byteSize(), arena, null)
                val modeSize = _drmModeModeInfo.layout().byteSize()
                val match = (0 until modeCount)
                    .map { modes.asSlice(it.toLong() * modeSize, modeSize) }
                    .firstOrNull {
                        _drmModeModeInfo.hdisplay(it).toInt() == mode.width &&
                            _drmModeModeInfo.vdisplay(it).toInt() == mode.height &&
                            _drmModeModeInfo.vrefresh(it) == mode.refreshRateHz
                    }
                    ?: error("Mode $mode not found on connector $connectorId")

                val blobData = match.toArray(ValueLayout.JAVA_BYTE)
                val blobSegment = arena.allocate(blobData.size.toLong())
                MemorySegment.copy(blobData, 0, blobSegment, ValueLayout.JAVA_BYTE, 0L, blobData.size)
                val outId = arena.allocate(ValueLayout.JAVA_INT)
                val ret = Xf86Drm.drmModeCreatePropertyBlob(
                    device.fd,
                    blobSegment,
                    blobData.size.toLong(),
                    outId,
                )
                check(ret == 0) { "drmModeCreatePropertyBlob failed with $ret" }
                outId.get(ValueLayout.JAVA_INT, 0)
            }
        } finally {
            Xf86Drm.drmModeFreeConnector(connector)
        }
    }
}
