package online.coffeeispower.jayland.blitTargets.drm

import online.coffeeispower.jayland.core.ColorMode

/**
 * DRM format fourccs and modifiers needed on the KMS side. These are C macros,
 * so they are not present in the jextract-generated [online.coffeeispower.jayland.drm.sys.Xf86Drm]
 * bindings and are defined here manually.
 */
object DrmFormats {
    const val XRGB8888 = 0x34325258
    const val XRGB2101010 = 0x30335258

    // `fourcc_mod_code(NONE, n) = n`, so `DRM_FORMAT_MOD_LINEAR` is 0 and
    // `DRM_FORMAT_MOD_INVALID` (fourcc_mod_code(NONE, DRM_FORMAT_RESERVED)) is
    // 0x00FFFFFFFFFFFFFF — all 56 low bits set.
    const val MOD_LINEAR = 0L
    const val MOD_INVALID = 0x00FFFFFFFFFFFFFFL
}

/** The DRM fourcc a [ColorMode] scanout buffer is presented as. */
val ColorMode.drmFourcc: Int
    get() = when (this) {
        ColorMode.RGBA8 -> DrmFormats.XRGB8888
        ColorMode.RGB10A2 -> DrmFormats.XRGB2101010
        ColorMode.RGBA16F, ColorMode.RGBA32F -> DrmFormats.XRGB8888
    }
