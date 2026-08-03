package online.coffeeispower.jayland.blitTargets.drm

import java.nio.file.Path

/**
 * Resolves the `/dev/dri/cardN` primary node owned by a given (major, minor)
 * DRM device, using [UdevContext] to walk `/sys/class/drm`. This turns the
 * `drmProps.primary` pair of a [online.coffeeispower.jayland.core.platform.linux.DrmGPU] into
 * the card fd the KMS side opens, sets master on, and scans out through.
 */
object CardPathResolver {

    /**
     * Returns the devnode path (e.g. `/dev/dri/card0`) of the card whose
     * `(major, minor)` matches, or null when no card matches.
     */
    @JvmStatic
    fun resolve(major: Int, minor: Int): Path? {
        UdevContext.create().use { udev ->
            for (i in 0 until 64) {
                val device = udev.deviceNewFromSubsystemSysname("drm", "card$i") ?: continue
                try {
                    val devAttr = udev.deviceGetSysattrValue(device, "dev") ?: continue
                    val parts = devAttr.split(':')
                    if (parts.size != 2) continue
                    val devMajor = parts[0].toIntOrNull() ?: continue
                    val devMinor = parts[1].toIntOrNull() ?: continue
                    if (devMajor == major && devMinor == minor) {
                        val devnode = udev.deviceGetDevnode(device) ?: continue
                        return Path.of(devnode)
                    }
                } finally {
                    udev.deviceUnref(device)
                }
            }
        }
        return null
    }
}
