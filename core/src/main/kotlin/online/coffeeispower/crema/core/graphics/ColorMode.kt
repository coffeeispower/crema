package online.coffeeispower.crema.core.graphics

/**
 * The pixel format of a [online.coffeeispower.crema.core.platform.linux.GPUScanoutImageBuffer]. Besides plain SDR (RGBA8), it covers the
 * wide-gamut and HDR formats a monitor may be driven with, e.g. RGB10A2 or
 * RGBA16F.
 */
enum class ColorMode(val bitsPerPixel: Int) {
    RGBA8(32),
    RGB10A2(32),
    RGBA16F(64),
    RGBA32F(128),
}