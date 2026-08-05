package online.coffeeispower.crema.blitTargets.drm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
import online.coffeeispower.crema.core.graphics.gpu.Submission
import online.coffeeispower.crema.core.graphics.presentation.Committer
import online.coffeeispower.crema.core.graphics.presentation.Frame
import online.coffeeispower.crema.core.graphics.presentation.FrameResult
import online.coffeeispower.crema.core.platform.linux.GPUScanoutImageBuffer
import online.coffeeispower.crema.core.platform.linux.DrmScanoutImageBuffer
import online.coffeeispower.crema.drm.sys.DrmFormats
import online.coffeeispower.crema.drm.sys.Xf86Drm
import online.coffeeispower.crema.utils.fds.Posix
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

/**
 * Presents frames with non-blocking atomic commits: each [commit] imports the
 * frame's DMA-BUF into KMS, attaches it to the primary plane with the frame's
 * in-fence as `IN_FENCE_FD` (the kernel waits on the submission, never us), and
 * suspends until *that* frame's page flip event arrives on the [DRMEventLoop]
 * reactor. The render loop races ahead of the flips — several frames can be
 * queued at once (bounded by the swapchain depth) — but the atomic commits
 * themselves are serialized: the kernel allows only one page flip per CRTC at a
 * time (a second concurrent commit returns EBUSY), so the next frame is
 * submitted only when the previous flip's event arrives. The CPU records the
 * next frame while the GPU renders the previous one and the kernel scans out
 * the one before that.
 *
 * Buffer ownership: once [commit] has been called, this committer owns
 * [Frame.buffer] and returns it to the output's
 * [online.coffeeispower.crema.core.graphics.presentation.Swapchain] exactly once — at the flip that
 * replaces it (captured as [FlipState.replaced] at commit time), or immediately
 * when [commit] fails *before* the kernel was asked to scan it out (so one bad
 * frame neither leaks a slot nor double-releases a buffer). The kernel
 * serializes commits per CRTC, so page-flip events arrive in the same order the
 * frames were committed and the in-flight queue pops from the front.
 *
 * The submission is owned here too: it is closed once the kernel has waited on
 * the in-fence (page-flip completion is the only point where the GPU is
 * provably done with it), and after a failed commit only once [Submission.latch]
 * reports completion, so a semaphore is never destroyed while the GPU is still
 * executing it.
 *
 * Concurrency: the flip states (the awaiting continuation, the frame sequence
 * number, the buffer being scanned out, the submission and the buffer each flip
 * displaces) live in the [inFlight] deque guarded by [inFlightLock]. The render
 * thread enqueues states in [commit] and presents only when its enqueue made
 * the queue non-empty; the reactor thread pops the front in [onPageFlip] and
 * presents the new head once the previous flip completed, so exactly one
 * atomic commit is ever in flight (a present can only run when no flip is
 * pending). [lastDisplayed] — read by commit to compute what the next flip
 * displaces — is only touched under the same lock, so no state is published
 * through separately-read plain fields on weakly-ordered hardware.
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

    private val inFlightLock = Any()

    /** The flips currently in flight, in commit order (page-flip events arrive in the same order). */
    private val inFlight = ArrayDeque<FlipState>()

    /** The buffer the kernel is scanning out with no pending flip to replace it. Guarded by [inFlightLock]. */
    private var lastDisplayed: GPUScanoutImageBuffer? = null

    private var frameSeq = 0L
    private var currentFbId = 0
    private var closed = false

    override suspend fun commit(frame: Frame): FrameResult {
        require(frame.buffer is DrmScanoutImageBuffer) { "DRM can only present DrmScanoutBuffer frames" }
        val seq = ++frameSeq
        val state: FlipState
        val isHead: Boolean
        synchronized(inFlightLock) {
            val replaced = inFlight.lastOrNull()?.buffer ?: lastDisplayed
            state = FlipState(CompletableDeferred(), seq, frame.buffer, frame.submission, replaced)
            inFlight.addLast(state)
            // event presents the new head.
            isHead = inFlight.size == 1
        }
        if (isHead) presentHead(state)
        return state.deferred.await()
    }

    /**
     * Presents the head of the queue, converting any failure into a failed
     * state instead of an exception escaping the commit's enqueue.
     */
    private suspend fun presentHead(state: FlipState) {
        try {
            present(state)
        } catch (e: Throwable) {
            failState(state, "presenting frame ${state.seq}", e)
        }
    }

    /**
     * Presents the frame queued behind the flip that just completed, submitting
     * the next head after any failure (each failed head is popped by
     * [failState], so this always makes progress). Runs on the reactor thread.
     */
    private fun presentNextQueued() {
        while (true) {
            val head: FlipState = synchronized(inFlightLock) { inFlight.firstOrNull() } ?: return
            try {
                present(head)
                return
            } catch (e: Throwable) {
                runBlocking { failState(head, "presenting queued frame ${head.seq}", e) }
            }
        }
    }

    /**
     * Handles a frame whose present() failed before the kernel was asked to
     * scan it out: pops it from the queue, returns its buffer to the swapchain
     * (exactly once), waits for the GPU to finish the submission before closing
     * it (NonCancellable, so a cancelled commit still releases it instead of
     * leaking it or closing the semaphore early), and wakes the suspended
     * commit with the error. Runs on the render thread for the frame just
     * enqueued, or on the reactor thread (via [runBlocking]) for a queued head.
     */
    private suspend fun failState(state: FlipState, reason: String, cause: Throwable) {
        synchronized(inFlightLock) { inFlight.remove(state) }
        output.swapchain.release(state.buffer)
        withContext(NonCancellable) {
            state.submission.latch.await()
            state.submission.close()
        }
        logger.warn { "$reason failed: $cause" }
        state.deferred.completeExceptionally(IllegalStateException("$reason failed", cause))
    }

    private fun present(state: FlipState) {
        val buffer = state.buffer as DrmScanoutImageBuffer
        val dmaBufFd = buffer.exportDmaBufFd()
        var fbId: Int
        try {
            val handle = importDmaBuf(dmaBufFd)
            fbId = addFramebuffer(buffer, handle)
            try {
                commitFlip(fbId, state.submission)
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

    /** Handles one completed page flip: submits the next queued frame and frees the buffer it replaced. */
    internal fun onPageFlip(sequence: Long, tvSec: Long, tvUsec: Long) {
        val state: FlipState
        val freed: GPUScanoutImageBuffer?
        synchronized(inFlightLock) {
            state = inFlight.removeFirstOrNull() ?: return
            freed = state.replaced
            lastDisplayed = state.buffer
        }
        // The kernel waited on the frame's in-fence, so the GPU is done with the
        // submission: releasing the semaphore now is safe.
        state.submission.close()
        freed?.let { output.swapchain.release(it) }
        // Submit the next queued frame before waking the render loop: the loop
        // resumes from this deferred and may enqueue a fresh frame, which would
        // otherwise race this thread's present for the same queue slot.
        presentNextQueued()
        val presentedAt = tvSec * 1_000_000_000L + tvUsec * 1_000L
        state.deferred.complete(FrameResult(presented = true, presentedAt = presentedAt, frameSeq = state.seq))
    }

    private fun importDmaBuf(dmaBufFd: Int): Int {
        val handle = outInt { buf ->
            val ret = Xf86Drm.drmPrimeFDToHandle(fd, dmaBufFd, buf)
            check(ret == 0) { "drmPrimeFDToHandle failed with $ret" }
        }
        check(handle != 0) { "drmPrimeFDToHandle returned handle 0" }
        return handle
    }

    private fun addFramebuffer(buffer: DrmScanoutImageBuffer, handle: Int): Int {
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

    private fun commitFlip(fbId: Int, submission: Submission) {
        val request = Xf86Drm.drmModeAtomicAlloc()
        if (request.address() == 0L) error("drmModeAtomicAlloc failed")
        try {
            val inFence = submission.exportInFenceFd()
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

    /**
     * The complete state of one flip. Kept in a single immutable value so the
     * publish (enqueue in [commit]) and the consumption (pop in [onPageFlip])
     * exchange everything the reactor thread needs while holding [inFlightLock]
     * — there is no separately-published mutable state that a stale read could
     * get wrong on weakly-ordered hardware.
     */
    private class FlipState(
        val deferred: CompletableDeferred<FrameResult>,
        val seq: Long,
        val buffer: GPUScanoutImageBuffer,
        val submission: Submission,
        val replaced: GPUScanoutImageBuffer?,
    )

    override fun close() {
        if (closed) return
        closed = true
        eventLoop.unregister(userData)
        if (currentFbId != 0) {
            Xf86Drm.drmModeRmFB(fd, currentFbId)
            currentFbId = 0
        }
    }
}
