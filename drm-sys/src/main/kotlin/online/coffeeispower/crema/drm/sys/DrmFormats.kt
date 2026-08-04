package online.coffeeispower.crema.drm.sys

import online.coffeeispower.crema.core.graphics.ColorMode

/**
 * DRM format fourccs and modifiers shared by every layer that touches KMS or
 * scanout images. These are C macros, so they are not present in the
 * jextract-generated [Xf86Drm] bindings and are defined here manually.
 *
 * This is the single source of truth for the values: the blit target (KMS
 * side) and the renderer (image-creation side) must agree on them, so the
 * duplicates that used to live in both modules were merged here (see
 * [ColorMode.drmFourcc] for the format a [ColorMode] scanout buffer maps to).
 */
object DrmFormats {
    const val XRGB8888 = 0x34325258
    const val XRGB2101010 = 0x30335258

    // `fourcc_mod_code(NONE, n) = n`, so `DRM_FORMAT_MOD_LINEAR` is 0 and
    // `DRM_FORMAT_MOD_INVALID` (fourcc_mod_code(NONE, DRM_FORMAT_RESERVED)) is
    // 0x00FFFFFFFFFFFFFF — all 56 low bits set.
    const val MOD_LINEAR = 0L
    const val MOD_INVALID = 0x00FFFFFFFFFFFFFFL

    /** Vendor id for Intel tiling, stored in the top byte of every `I915_FORMAT_MOD_*`. */
    const val MOD_VENDOR_INTEL = 0x100L

    /** Intel tile-row pitches in bytes: X tiles are 512B wide, Y/Yf tiles 128B. */
    const val I915_X_TILED = 1
    const val I915_Y_TILED = 2
    const val I915_YF_TILED = 3
}

/** The DRM fourcc a [ColorMode] scanout buffer is presented as. */
val ColorMode.drmFourcc: Int
    get() = when (this) {
        ColorMode.RGBA8 -> DrmFormats.XRGB8888
        ColorMode.RGB10A2 -> DrmFormats.XRGB2101010
        ColorMode.RGBA16F, ColorMode.RGBA32F -> DrmFormats.XRGB8888
    }
