package online.coffeeispower.jayland.blitTargets.drm

import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.monitors.Connector
import online.coffeeispower.jayland.core.monitors.ConnectorManager
import online.coffeeispower.jayland.core.graphics.gpu.GPU
import online.coffeeispower.jayland.core.monitors.Mode
import online.coffeeispower.jayland.core.monitors.Monitor
import online.coffeeispower.jayland.core.graphics.ColorMode
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.drm.sys._drmModeConnector
import online.coffeeispower.jayland.drm.sys._drmModeEncoder
import online.coffeeispower.jayland.drm.sys._drmModeModeInfo
import online.coffeeispower.jayland.drm.sys._drmModeRes
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Enumerates the connected connectors of the card behind [device], each bound
 * to the GPU that drives it and backed by [eventLoop] for its page flips.
 *
 * The structs returned by the libdrm helpers (`drmModeGetResources`,
 * `drmModeGetConnector`, `drmModeGetEncoder`) are libdrm's own types
 * (`_drmModeRes`, `_drmModeConnector`, ...), NOT the kernel uapi structs
 * (`drm_mode_get_connector`, ...) that jextract also generates from the ioctl
 * definitions in `drm.h`. The two families have different layouts, so accessor
 * classes must never be mixed.
 */
class DRMConnectorManager(
    private val device: DRMDevice,
    private val gpu: GPU,
    private val eventLoop: DRMEventLoop,
) : ConnectorManager {

    private val logger = KotlinLogging.logger {}

    override val connectors: List<Connector> by lazy { enumerate() }

    private fun enumerate(): List<Connector> {
        val resources = Xf86Drm.drmModeGetResources(device.fd)
        if (resources.address() == 0L) error("drmModeGetResources failed")
        val found = try {
            Arena.ofConfined().use { arena ->
                val connectorCount = _drmModeRes.count_connectors(resources)
                val connectorIds = _drmModeRes.connectors(resources)
                // Pointer segments read through an ADDRESS layout have an
                // unbounded byteSize(), so element counts must come from the
                // struct fields, never from segment size.
                val crtcCount = _drmModeRes.count_crtcs(resources)
                val crtcIds = _drmModeRes.crtcs(resources).toList(crtcCount, ValueLayout.JAVA_INT)
                val usedCrtcs = mutableSetOf<Int>()
                if (connectorCount <= 0) emptyList()
                else List(connectorCount) { i ->
                    connectorFor(
                        connectorIds.get(ValueLayout.JAVA_INT, i * ValueLayout.JAVA_INT.byteSize()),
                        crtcIds,
                        usedCrtcs,
                        arena,
                    )
                }.filterNotNull()
            }
        } finally {
            Xf86Drm.drmModeFreeResources(resources)
        }
        logger.info { "Found ${found.size} connected DRM connector(s)" }
        return found
    }

    private fun connectorFor(
        connectorId: Int,
        crtcIds: List<Int>,
        usedCrtcs: MutableSet<Int>,
        arena: Arena,
    ): Connector? {
        val connector = Xf86Drm.drmModeGetConnector(device.fd, connectorId)
        if (connector.address() == 0L) return null
        try {
            // 1 == DRM_MODE_CONNECTED
            val connection = _drmModeConnector.connection(connector)
            val modeCount = _drmModeConnector.count_modes(connector)
            if (connection != 1) {
                logger.debug { "Skipping connector $connectorId: not connected (connection=$connection)" }
                return null
            }
            if (modeCount <= 0) {
                logger.debug { "Skipping connector $connectorId: no usable modes" }
                return null
            }

            // The connector's encoder says which CRTCs can drive it
            // (`possible_crtcs`, a bitmask over the resources' crtc array) and,
            // when a modeset is active, which one is currently bound. On a
            // fresh DRM master nothing is bound yet, so the bound CRTC is a
            // hint, not a requirement: fall back to the first CRTC this
            // connector can use that no other connector took.
            val encoderId = _drmModeConnector.encoder_id(connector)
            val (possibleCrtcs, boundCrtcId) = if (encoderId != 0) {
                val encoder = Xf86Drm.drmModeGetEncoder(device.fd, encoderId)
                if (encoder.address() == 0L) {
                    0L to 0
                } else {
                    try {
                        _drmModeEncoder.possible_crtcs(encoder).toLong() to
                            _drmModeEncoder.crtc_id(encoder)
                    } finally {
                        Xf86Drm.drmModeFreeEncoder(encoder)
                    }
                }
            } else {
                0L to 0
            }

            val boundIndex = if (boundCrtcId != 0) crtcIds.indexOf(boundCrtcId) else -1
            val crtcIndex = if (boundIndex >= 0) {
                boundIndex
            } else {
                crtcIds.indices.firstOrNull { possibleCrtcs and (1L shl it) != 0L && it !in usedCrtcs }
            } ?: run {
                logger.debug {
                    "Skipping connector $connectorId: no CRTC available " +
                        "(possible=0x${possibleCrtcs.toString(16)}, bound=$boundCrtcId)"
                }
                return null
            }
            usedCrtcs += crtcIndex
            val crtcId = crtcIds[crtcIndex]

            val modes = _drmModeConnector.modes(connector)
                .reinterpret(modeCount.toLong() * _drmModeModeInfo.layout().byteSize(), arena, null)
            val modeSize = _drmModeModeInfo.layout().byteSize()
            val preferred = (0 until modeCount)
                .map { modes.asSlice(it.toLong() * modeSize, modeSize) }
                .firstOrNull { _drmModeModeInfo.type(it) and Xf86Drm.DRM_MODE_TYPE_PREFERRED() != 0 }
                ?: modes.asSlice(0, modeSize)

            val mode = Mode(
                width = _drmModeModeInfo.hdisplay(preferred).toInt(),
                height = _drmModeModeInfo.vdisplay(preferred).toInt(),
                refreshRateHz = _drmModeModeInfo.vrefresh(preferred),
            )
            val monitor = Monitor(
                name = connectorName(connector),
                width = mode.width,
                height = mode.height,
                refreshRateHz = mode.refreshRateHz,
                supportedColorModes = listOf(ColorMode.RGBA8, ColorMode.RGB10A2),
            )
            logger.debug {
                "Connector $connectorId (${monitor.name}) -> crtc $crtcId " +
                    "(${mode.width}x${mode.height}@${mode.refreshRateHz}Hz)"
            }
            return DRMConnector(
                enabled = true,
                monitor = monitor,
                preferredMode = mode,
                device = device,
                connectorId = connectorId,
                crtcId = crtcId,
                crtcIndex = crtcIndex,
                eventLoop = eventLoop,
                gpu = gpu,
            )
        } finally {
            Xf86Drm.drmModeFreeConnector(connector)
        }
    }

    private fun connectorName(connector: MemorySegment): String {
        val type = _drmModeConnector.connector_type(connector)
        val typeId = _drmModeConnector.connector_type_id(connector)
        val typeName = Xf86Drm.drmModeGetConnectorTypeName(type).cString()
        return "$typeName-$typeId"
    }

    private fun MemorySegment.toList(count: Int, layout: ValueLayout.OfInt): List<Int> =
        if (count <= 0) emptyList()
        else List(count) { get(layout, it.toLong() * layout.byteSize()) }

    override fun close() {
        // Connector objects themselves own no resources beyond the card's
        // mode info, which was freed during enumeration.
    }
}
