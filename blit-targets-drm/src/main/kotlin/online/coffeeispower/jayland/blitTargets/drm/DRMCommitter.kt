package online.coffeeispower.jayland.blitTargets.drm

import kotlinx.coroutines.CompletableDeferred
import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.jayland.core.Committer
import online.coffeeispower.jayland.core.Frame
import online.coffeeispower.jayland.core.FrameResult
import online.coffeeispower.jayland.core.GPUScanoutBuffer
import online.coffeeispower.jayland.core.platform.linux.DrmScanoutBuffer
import online.coffeeispower.jayland.drm.sys.Xf86Drm
import online.coffeeispower.jayland.utils.fds.Posix
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.util.concurrent.atomic.AtomicReference

/**
 * Presents frames with non-blocking atomic commits: each [commit] imports the
 * frame's DMA-BUF into KMS, attaches it to the primary plane with the frame's
 * in-fence as `IN_FENCE_FD` (the kernel waits on the submission, never us), and
 * suspends until the page flip event arrives on the [DRMEventLoop] reactor.
 *
 * The buffer that was just replaced by the flip is returned to the output's
 * [online.coffeeispower.jayland.core.Swapchain] on completion, so with a
 * 2-deep pool one buffer is always being scanned out while the other is
 * cleared for the next frame.
 */
class DRMCommitter internal constructor(
    private val output: DRMOutput,
    private val eventLoop: DRMEventLoop,
) : Committer {

    private val logger = KotlinLogging.logger {}

    private val fd: Int get() = output.device.fd
    private val props: DrmProperties get() = output.props
    private val crtcId: Int get() = output.crtcId
    private val planeId: Int get() = output.planeId
    private val connectorId: Int get() = output.connectorId
    private val modeBlobId: Int get() = output.modeBlobId

    private val userData: MemorySegment = eventLoop.register(this)
    private val pending = AtomicReference<CompletableDeferred<FrameResult>>()

    private var frameSeq = 0L
    private var lastSeq = 0L
    private var currentFbId = 0
    private var lastDisplayed: GPUScanoutBuffer? = null
    private var inFlightBuffer: GPUScanoutBuffer? = null
    private var closed = false

    override suspend fun commit(frame: Frame): FrameResult {
        require(frame.buffer is DrmScanoutBuffer) { "DRM can only present DrmScanoutBuffer frames" }
        val seq = ++frameSeq
        val deferred = CompletableDeferred<FrameResult>()
        check(pending.compareAndSet(null, deferred)) { "A commit is already in flight on this output" }
        try {
            present(frame, seq)
        } catch (e: Throwable) {
            pending.set(null)
            throw e
        }
        return deferred.await()
    }

    private fun present(frame: Frame, seq: Long) {
        val buffer = frame.buffer as DrmScanoutBuffer
        inFlightBuffer = buffer
        lastSeq = seq
        val dmaBufFd = buffer.exportDmaBufFd()
        var fbId = 0
        try {
            val handle = importDmaBuf(dmaBufFd)
            fbId = addFramebuffer(buffer, handle)
            try {
                commitFlip(fbId, frame)
            } catch (e: Throwable) {
                Xf86Drm.drmModeRmFB(fd, fbId)
                throw e
            }
        } finally {
            Posix.close(dmaBufFd)
        }
        // The CRTC no longer references the previous fb; the kernel kept its
        // own reference until the flip, so it is safe to drop ours now.
        if (currentFbId != 0) {
            Xf86Drm.drmModeRmFB(fd, currentFbId)
        }
        currentFbId = fbId
    }

    /** Handles one completed page flip: resumes the commit and frees the buffer it replaced. */
    internal fun onPageFlip(sequence: Long, tvSec: Long, tvUsec: Long) {
        val deferred = pending.getAndSet(null)
        if (deferred != null) {
            val freed = lastDisplayed
            lastDisplayed = inFlightBuffer
            inFlightBuffer = null
            freed?.let { output.swapchain.release(it) }
            val presentedAt = tvSec * 1_000_000_000L + tvUsec * 1_000L
            deferred.complete(FrameResult(presented = true, presentedAt = presentedAt, frameSeq = lastSeq))
        }
    }

    private fun importDmaBuf(dmaBufFd: Int): Int {
        val handle = outInt { buf ->
            val ret = Xf86Drm.drmPrimeFDToHandle(fd, dmaBufFd, buf)
            check(ret == 0) { "drmPrimeFDToHandle failed with $ret" }
        }
        check(handle != 0) { "drmPrimeFDToHandle returned handle 0" }
        return handle
    }

    private fun addFramebuffer(buffer: DrmScanoutBuffer, handle: Int): Int {
        return Arena.ofConfined().use { arena ->
            val handles = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val pitches = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val offsets = arena.allocate(ValueLayout.JAVA_INT, 4L)
            val modifiers = arena.allocate(ValueLayout.JAVA_LONG, 4L)
            val outId = arena.allocate(ValueLayout.JAVA_INT)
            handles.set(ValueLayout.JAVA_INT, 0L, handle)
            pitches.set(ValueLayout.JAVA_INT, 0L, buffer.stride)
            offsets.set(ValueLayout.JAVA_INT, 0L, 0)
            // The kernel requires all four modifier entries to carry the same
            // value when DRM_MODE_FB_MODIFIERS is set (a zero entry for a plane
            // that should not be there is a mismatch, not "unused"), so mirror
            // the modifier across every plane slot like the reference's
            // add_planar_framebuffer does.
            modifiers.set(ValueLayout.JAVA_LONG, 0L, buffer.drmModifier)
            modifiers.set(ValueLayout.JAVA_LONG, 8L, buffer.drmModifier)
            modifiers.set(ValueLayout.JAVA_LONG, 16L, buffer.drmModifier)
            modifiers.set(ValueLayout.JAVA_LONG, 24L, buffer.drmModifier)
            // The kernel only honors modifier[] when DRM_MODE_FB_MODIFIERS is
            // set; passing a non-INVALID modifier without the flag fails with
            // EINVAL, so the flag must follow the modifier, not how the image
            // was created (mirrors the reference implementation's use of
            // FbCmd2Flags::MODIFIERS whenever the BO has a real modifier).
            val flags = if (buffer.drmModifier != DrmFormats.MOD_INVALID) {
                Xf86Drm.DRM_MODE_FB_MODIFIERS()
            } else {
                0
            }
            logger.trace {
                "addFB2 ${buffer.width}x${buffer.height} fourcc=0x${buffer.drmFormat.toString(16)} " +
                    "pitch=${buffer.stride} modifier=${buffer.drmModifier} " +
                    "usesExplicit=${buffer.usesExplicitModifier} flags=$flags handle=$handle"
            }
            val ret = Xf86Drm.drmModeAddFB2WithModifiers(
                fd, buffer.width, buffer.height, buffer.drmFormat,
                handles, pitches, offsets, modifiers, outId, flags,
            )
            if (ret == 0) {
                return@use outId.get(ValueLayout.JAVA_INT, 0)
            }
            // Some drivers reject linear buffers through the ADDFB2 ioctl too;
            // fall back to the legacy single-plane call (like the reference
            // implementation's add_framebuffer(bo, 24, 32)).
            val legacyRet = Xf86Drm.drmModeAddFB(
                fd, buffer.width, buffer.height, 24.toByte(), 32.toByte(),
                buffer.stride, handle, outId,
            )
            check(legacyRet == 0) {
                "drmModeAddFB2WithModifiers failed with $ret, drmModeAddFB fallback failed with $legacyRet"
            }
            outId.get(ValueLayout.JAVA_INT, 0)
        }
    }

    private fun commitFlip(fbId: Int, frame: Frame) {
        val request = Xf86Drm.drmModeAtomicAlloc()
        if (request.address() == 0L) error("drmModeAtomicAlloc failed")
        try {
            val inFence = frame.submission.exportInFenceFd()
            try {
                addProperty(request, planeId, props.planeCrtcId, crtcId.toLong())
                addProperty(request, planeId, props.planeFbId, fbId.toLong())
                addProperty(request, planeId, props.planeInFenceFd, inFence.toLong())
                addProperty(request, crtcId, props.crtcModeId, modeBlobId.toLong())
                addProperty(request, crtcId, props.crtcActive, 1L)
                addProperty(request, connectorId, props.connectorCrtcId, crtcId.toLong())
                val flags = Xf86Drm.DRM_MODE_ATOMIC_NONBLOCK() or
                    Xf86Drm.DRM_MODE_ATOMIC_ALLOW_MODESET() or
                    Xf86Drm.DRM_MODE_PAGE_FLIP_EVENT()
                val ret = Xf86Drm.drmModeAtomicCommit(fd, request, flags, userData)
                check(ret == 0) { "drmModeAtomicCommit failed with $ret" }
            } finally {
                // The kernel dup()ed the in-fence for this commit; drop ours.
                if (inFence >= 0) Posix.close(inFence)
            }
        } finally {
            Xf86Drm.drmModeAtomicFree(request)
        }
    }

    private fun addProperty(request: MemorySegment, objectId: Int, propertyId: Int, value: Long) {
        val ret = Xf86Drm.drmModeAtomicAddProperty(request, objectId, propertyId, value)
        // Returns the request's property count on success (libdrm <2.4.134
        // returns 0; newer returns the cursor), or a negative errno on failure.
        // Only the commit itself reports real errors (EINVAL, EBUSY, ...).
        check(ret >= 0) { "drmModeAtomicAddProperty($objectId, $propertyId) failed with $ret" }
    }

    private inline fun outInt(block: (MemorySegment) -> Unit): Int =
        Arena.ofConfined().use { arena ->
            val segment = arena.allocate(ValueLayout.JAVA_INT)
            block(segment)
            segment.get(ValueLayout.JAVA_INT, 0L)
        }

    override fun close() {
        if (closed) return
        closed = true
        eventLoop.unregister(this, userData)
        if (currentFbId != 0) {
            Xf86Drm.drmModeRmFB(fd, currentFbId)
            currentFbId = 0
        }
    }
}
