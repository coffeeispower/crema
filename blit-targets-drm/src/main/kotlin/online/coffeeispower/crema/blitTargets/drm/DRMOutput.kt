package online.coffeeispower.crema.blitTargets.drm

import online.coffeeispower.crema.core.graphics.presentation.Committer
import online.coffeeispower.crema.core.monitors.Mode
import online.coffeeispower.crema.core.monitors.Monitor
import online.coffeeispower.crema.core.monitors.Output
import online.coffeeispower.crema.core.graphics.presentation.Swapchain
import online.coffeeispower.crema.core.units.ScaleFactor
import online.coffeeispower.crema.drm.sys.Xf86Drm
import java.lang.foreign.MemorySegment

/**
 * An enabled output: a swapchain of scanout buffers, the plane/CRTC they flip
 * onto, and the committer that presents them. [committer] suspends until each
 * submitted frame's page flip has completed.
 */
class DRMOutput(
    override val monitor: Monitor,
    override val mode: Mode,
    override val swapchain: Swapchain,
    internal val device: DRMDevice,
    internal val crtcId: Int,
    internal val planeId: Int,
    internal val connectorId: Int,
    internal val props: DrmProperties,
    internal val modeBlobId: Int,
    eventLoop: DRMEventLoop,
) : Output {

    @Volatile
    override var detached = false
        private set

    override val committer: Committer = DRMCommitter(this, eventLoop)

    // No user-facing scale configuration exists yet, so outputs start at 1:1.
    override val scaleFactor: ScaleFactor = ScaleFactor.ONE

    override fun close() {
        if (detached) return
        detached = true
        committer.close()
        disable()
        swapchain.close()
    }

    /** Turns the CRTC off and frees the mode blob. */
    private fun disable() {
        val request = Xf86Drm.drmModeAtomicAlloc()
        if (request.address() != 0L) {
            try {
                Xf86Drm.drmModeAtomicAddProperty(request, planeId, props.planeCrtcId, crtcId.toLong())
                Xf86Drm.drmModeAtomicAddProperty(request, planeId, props.planeFbId, 0L)
                Xf86Drm.drmModeAtomicAddProperty(request, crtcId, props.crtcActive, 0L)
                Xf86Drm.drmModeAtomicCommit(
                    device.fd,
                    request,
                    Xf86Drm.DRM_MODE_ATOMIC_ALLOW_MODESET(),
                    MemorySegment.NULL,
                )
            } finally {
                Xf86Drm.drmModeAtomicFree(request)
            }
        }
        Xf86Drm.drmModeDestroyPropertyBlob(device.fd, modeBlobId)
    }
}
