package online.coffeeispower.jayland.blitTargets.drm

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.graphics.ColorMode
import online.coffeeispower.jayland.core.monitors.Connector
import online.coffeeispower.jayland.core.graphics.gpu.GPU
import online.coffeeispower.jayland.core.monitors.Mode
import online.coffeeispower.jayland.core.monitors.Monitor
import online.coffeeispower.jayland.core.monitors.Output
import online.coffeeispower.jayland.core.graphics.presentation.Swapchain
import online.coffeeispower.jayland.core.graphics.gpu.VRam
import online.coffeeispower.jayland.drm.sys.DrmFormats
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmModeConnector
import online.coffeeispower.jayland.drm.sys._drmModeModeInfo
import online.coffeeispower.jayland.drm.sys._drmModePlane
import online.coffeeispower.jayland.drm.sys._drmModePlaneRes
import online.coffeeispower.jayland.drm.sys._drmModePropertyBlob
import online.coffeeispower.jayland.drm.sys.drmFourcc
import online.coffeeispower.jayland.drm.sys.drm_format_modifier
import online.coffeeispower.jayland.drm.sys.drm_format_modifier_blob
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * A connected DRM connector (a physical port). [enable] wires up the swapchain
 * and the plane/CRTC pair it scans out through, returning a [DRMOutput]. The
 * swapchain's buffers are created with the modifiers the plane advertises in
 * its `IN_FORMATS` property, so the output drives the buffer layout instead of
 * guessing what KMS accepts.
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
        val planeId = findPrimaryPlane(colorMode.drmFourcc)
        val modifiers = planeScanoutModifiers(device.fd, planeId, colorMode.drmFourcc)
        logger.debug {
            "Plane $planeId scanout modifiers for fourcc 0x${colorMode.drmFourcc.toString(16)}: $modifiers"
        }
        val swapchain = Swapchain(mode.width, mode.height, colorMode, depth = 2, vram, allowedModifiers = modifiers)
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

    /**
     * Reads the plane's `IN_FORMATS` property and returns the modifiers it can
     * scan [fourcc] with, ordered so [DrmFormats.MOD_LINEAR] (the one layout the
     * renderer is guaranteed to produce and every driver accepts) is first. When
     * the property or its blob is unavailable, falls back to LINEAR only.
     */
    private fun planeScanoutModifiers(fd: Int, planeId: Int, fourcc: Int): List<Long> {
        val blobId = DrmProperties.propertyValue(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "IN_FORMATS")
            ?: return listOf(DrmFormats.MOD_LINEAR)
        if (blobId == 0L) return listOf(DrmFormats.MOD_LINEAR)
        val blob = Xf86Drm.drmModeGetPropertyBlob(fd, blobId.toInt())
        if (blob.address() == 0L) return listOf(DrmFormats.MOD_LINEAR)
        return try {
            readFormatModifiers(blob, fourcc)
        } finally {
            Xf86Drm.drmModeFreePropertyBlob(blob)
        }
    }

    /**
     * Extracts the modifiers for [fourcc] from an `IN_FORMATS` blob (the kernel's
     * `drm_format_modifier_blob`: a header, a `formats[]` fourcc table and a
     * `drm_format_modifier` array whose `formats` bitmask covers the table with a
     * 64-entry sliding window). Returns LINEAR-only when parsing fails so a
     * hostile blob can never crash enable.
     */
    companion object {
        internal fun readFormatModifiers(blob: MemorySegment, fourcc: Int): List<Long> {
            val data = _drmModePropertyBlob.data(blob)
            val length = _drmModePropertyBlob.length(blob)
            val headerSize = drm_format_modifier_blob.layout().byteSize()
            if (data.address() == 0L || length < headerSize) return listOf(DrmFormats.MOD_LINEAR)
            return Arena.ofConfined().use { arena ->
                val blobSegment = data.reinterpret(length.toLong(), arena, null)
                val countFormats = drm_format_modifier_blob.count_formats(blobSegment)
                val formatsOffset = drm_format_modifier_blob.formats_offset(blobSegment)
                val countModifiers = drm_format_modifier_blob.count_modifiers(blobSegment)
                val modifiersOffset = drm_format_modifier_blob.modifiers_offset(blobSegment)
                val formatsBytes = countFormats.toLong() * 4L
                val modifiersBytes = countModifiers.toLong() * drm_format_modifier.layout().byteSize()
                // A malformed/truncated blob must never crash enable: bail out to the
                // LINEAR default when the declared tables fall outside the blob.
                if (formatsOffset.toLong() + formatsBytes > length ||
                    modifiersOffset.toLong() + modifiersBytes > length
                ) {
                    return@use listOf(DrmFormats.MOD_LINEAR)
                }
                val formatIndex = (0 until countFormats).firstOrNull {
                    blobSegment.get(ValueLayout.JAVA_INT, formatsOffset.toLong() + it * 4L) == fourcc
                } ?: return@use listOf(DrmFormats.MOD_LINEAR)
                val modifiers = mutableListOf<Long>()
                val modifierSize = drm_format_modifier.layout().byteSize()
                for (i in 0 until countModifiers) {
                    val entry = blobSegment.asSlice(modifiersOffset.toLong() + i * modifierSize, modifierSize)
                    val window = drm_format_modifier.offset(entry)
                    val bit = formatIndex - window
                    if (bit in 0 until 64 && (drm_format_modifier.formats(entry) shr bit) and 1L != 0L) {
                        modifiers += drm_format_modifier.modifier(entry)
                    }
                }
                if (DrmFormats.MOD_LINEAR in modifiers) {
                    listOf(DrmFormats.MOD_LINEAR) + modifiers.filter { it != DrmFormats.MOD_LINEAR }
                } else if (modifiers.isNotEmpty()) {
                    modifiers
                } else {
                    listOf(DrmFormats.MOD_LINEAR)
                }
            }
        }
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
