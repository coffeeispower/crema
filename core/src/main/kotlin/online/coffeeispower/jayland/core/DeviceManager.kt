package online.coffeeispower.jayland.core

interface ConnectorManager : AutoCloseable {
    val connectors: List<Connector>
}

interface DeviceManager : AutoCloseable {
    val gpus: List<GPU>
}

interface GPU : AutoCloseable {
    val name: String
    val vram: VRam
}

data class DrmProps(
    val render: Pair<Long, Long>?,
    val primary: Pair<Long, Long>?,
)

/**
 * The pixel format of a [GPUScanoutBuffer]. Besides plain SDR (RGBA8), it covers the
 * wide-gamut and HDR formats a monitor may be driven with, e.g. RGB10A2 or
 * RGBA16F.
 */
enum class ColorMode(val bitsPerPixel: Int) {
    RGBA8(32),
    RGB10A2(32),
    RGBA16F(64),
    RGBA32F(128),
}

interface VRam : AutoCloseable {
    fun allocateBufferForScanout(width: Int, height: Int, colorMode: ColorMode = ColorMode.RGBA8): GPUScanoutBuffer
}

interface GPUScanoutBuffer : AutoCloseable {
    val width: Int
    val height: Int
    val colorMode: ColorMode
    val owner: GPU
}
