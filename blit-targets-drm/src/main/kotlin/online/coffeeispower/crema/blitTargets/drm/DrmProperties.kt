package online.coffeeispower.crema.blitTargets.drm

import online.coffeeispower.crema.drm.sys.Xf86Drm
import online.coffeeispower.crema.drm.sys._drmModeObjectProperties
import online.coffeeispower.crema.drm.sys._drmModeProperty
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * The atomic property ids a single card uses to drive scanout. Property ids
 * are card-global but the ids are resolved per object type, so this bundles
 * everything one [DRMOutput] needs for its plane, CRTC and connector.
 */
class DrmProperties(
    val planeCrtcId: Int,
    val planeFbId: Int,
    val planeInFenceFd: Int,
    val planeType: Int,
    val crtcModeId: Int,
    val crtcActive: Int,
    val connectorCrtcId: Int,
) {

    companion object {
        /** Resolves the atomic property ids for one plane/CRTC/connector triple. */
        fun resolve(fd: Int, planeId: Int, crtcId: Int, connectorId: Int): DrmProperties {
            val planeType = propertyId(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "type")
                ?: error("Plane $planeId has no `type` property")
            return DrmProperties(
                planeCrtcId = propertyId(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "CRTC_ID")
                    ?: error("Plane $planeId has no `CRTC_ID` property"),
                planeFbId = propertyId(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "FB_ID")
                    ?: error("Plane $planeId has no `FB_ID` property"),
                planeInFenceFd = propertyId(fd, planeId, Xf86Drm.DRM_MODE_OBJECT_PLANE(), "IN_FENCE_FD")
                    ?: error("Plane $planeId has no `IN_FENCE_FD` property"),
                planeType = planeType,
                crtcModeId = propertyId(fd, crtcId, Xf86Drm.DRM_MODE_OBJECT_CRTC(), "MODE_ID")
                    ?: error("CRTC $crtcId has no `MODE_ID` property"),
                crtcActive = propertyId(fd, crtcId, Xf86Drm.DRM_MODE_OBJECT_CRTC(), "ACTIVE")
                    ?: error("CRTC $crtcId has no `ACTIVE` property"),
                connectorCrtcId = propertyId(fd, connectorId, Xf86Drm.DRM_MODE_OBJECT_CONNECTOR(), "CRTC_ID")
                    ?: error("Connector $connectorId has no `CRTC_ID` property"),
            )
        }

        /** Looks up the id of the property [name] on an object, or null. */
        fun propertyId(fd: Int, objectId: Int, objectType: Int, name: String): Int? {
            val props = Xf86Drm.drmModeObjectGetProperties(fd, objectId, objectType)
            if (props.address() == 0L) return null
            var result: Int? = null
            try {
                Arena.ofConfined().use { arena ->
                    val count = _drmModeObjectProperties.count_props(props)
                    if (count > 0) {
                        val ids = _drmModeObjectProperties.props(props)
                            .reinterpret(count.toLong() * ValueLayout.JAVA_INT.byteSize(), arena, null)
                        for (i in 0 until count) {
                            val prop = Xf86Drm.drmModeGetProperty(fd, ids.get(ValueLayout.JAVA_INT, i * 4L))
                            if (prop.address() == 0L) continue
                            try {
                                val propName = _drmModeProperty.name(prop).cString()
                                if (propName == name) {
                                    result = ids.get(ValueLayout.JAVA_INT, i * 4L)
                                    break
                                }
                            } finally {
                                Xf86Drm.drmModeFreeProperty(prop)
                            }
                        }
                    }
                }
            } finally {
                Xf86Drm.drmModeFreeObjectProperties(props)
            }
            return result
        }

        /** Reads the current value of the property [name] on an object, or null. */
        fun propertyValue(fd: Int, objectId: Int, objectType: Int, name: String): Long? {
            val props = Xf86Drm.drmModeObjectGetProperties(fd, objectId, objectType)
            if (props.address() == 0L) return null
            var result: Long? = null
            try {
                Arena.ofConfined().use { arena ->
                    val count = _drmModeObjectProperties.count_props(props)
                    if (count > 0) {
                        val ids = _drmModeObjectProperties.props(props)
                            .reinterpret(count.toLong() * ValueLayout.JAVA_INT.byteSize(), arena, null)
                        val values = _drmModeObjectProperties.prop_values(props)
                            .reinterpret(count.toLong() * ValueLayout.JAVA_LONG.byteSize(), arena, null)
                        for (i in 0 until count) {
                            val prop = Xf86Drm.drmModeGetProperty(fd, ids.get(ValueLayout.JAVA_INT, i * 4L))
                            if (prop.address() == 0L) continue
                            try {
                                val propName = _drmModeProperty.name(prop).cString()
                                if (propName == name) {
                                    result = values.get(ValueLayout.JAVA_LONG, i * 8L)
                                    break
                                }
                            } finally {
                                Xf86Drm.drmModeFreeProperty(prop)
                            }
                        }
                    }
                }
            } finally {
                Xf86Drm.drmModeFreeObjectProperties(props)
            }
            return result
        }
    }
}
