package online.coffeeispower.jayland.core.platform.linux

/**
 * A [GPUScanoutBuffer] whose backing memory can be imported into the DRM/KMS
 * stack, exposing the format, tiling modifier and DMA-BUF fd the kernel needs
 * to build a scanout framebuffer. Mirrors [online.coffeeispower.jayland.core.platform.linux.DrmGPU]
 * by staying Linux-specific while the platform-agnostic [GPUScanoutBuffer]
 * stays clean.
 */
interface DrmScanoutBuffer : GPUScanoutBuffer {
    /** The `DRM_FORMAT_*` fourcc of the buffer (e.g. `DRM_FORMAT_XRGB8888`). */
    val drmFormat: Int

    /** The `DRM_FORMAT_MOD_*` tiling modifier (e.g. `DRM_FORMAT_MOD_LINEAR`). */
    val drmModifier: Long

    /**
     * Whether the buffer's DMA-BUF carries its [drmModifier] in its `mod_info`,
     * i.e. the image was created through the DRM format modifier extension.
     * When false the image was created with plain (implicit) linear tiling, so
     * KMS may need the legacy framebuffer path to present it. The committer
     * passes the modifier explicitly whenever [drmModifier] is not
     * `DRM_FORMAT_MOD_INVALID`.
     */
    val usesExplicitModifier: Boolean

    /** Row pitch in bytes of the first plane, as the kernel needs it in `drm_mode_fb_cmd2`. */
    val stride: Int

    /**
     * Exports the buffer's memory as a DMA-BUF file descriptor for import into
     * KMS (via `drmPrimeFDToHandle`). The caller owns and must close the fd.
     */
    fun exportDmaBufFd(): Int
}
