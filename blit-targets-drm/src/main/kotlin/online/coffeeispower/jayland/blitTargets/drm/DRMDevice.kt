package online.coffeeispower.jayland.blitTargets.drm

import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.utils.fds.Posix
import java.nio.file.Path

/**
 * The open DRM primary card: the fd the KMS side does all its ioctls through,
 * plus DRM master ownership and the client caps atomic commits require.
 *
 * Opening the card and grabbing master needs privileges (root or
 * `CAP_SYS_ADMIN`); callers that cannot get them should treat [open] as
 * unsupported and fall back to another presentation backend.
 */
class DRMDevice private constructor(val fd: Int) : AutoCloseable {

    init {
        check(Xf86Drm.drmSetClientCap(fd, Xf86Drm.DRM_CLIENT_CAP_UNIVERSAL_PLANES().toLong(), 1L) == 0) {
            "drmSetClientCap(UNIVERSAL_PLANES) failed"
        }
        check(Xf86Drm.drmSetClientCap(fd, Xf86Drm.DRM_CLIENT_CAP_ATOMIC().toLong(), 1L) == 0) {
            "drmSetClientCap(ATOMIC) failed"
        }
    }

    override fun close() {
        Xf86Drm.drmDropMaster(fd)
        Posix.close(fd)
    }

    companion object {
        private const val O_RDWR = 2

        fun open(path: Path): DRMDevice {
            val fd = Posix.open(path.toString(), O_RDWR)
            check(fd >= 0) { "Cannot open DRM device $path (fd=$fd)" }

            check(Xf86Drm.drmSetMaster(fd) == 0) { "drmSetMaster($path) failed (are we root?)" }
            return DRMDevice(fd)
        }
    }
}
