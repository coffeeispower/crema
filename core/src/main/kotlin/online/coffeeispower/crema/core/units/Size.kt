package online.coffeeispower.crema.core.units

import kotlin.math.round

/**
 * A size measured in physical pixels: the actual, addressable pixels of an
 * output's framebuffer.
 *
 * The width and height are carried as [T]. The compositor always uses
 * [Double] for [T], even for physical pixels: fractional scale factors
 * (1.25, 1.5, ...) mean [PhysicalSize.toLogical] and [LogicalSize.toPhysical]
 * must be able to represent in-between values, and an integer physical size
 * would introduce rounding errors on the round trip. Integer widths/heights
 * are only coerced at the hardware boundary (swapchain/buffer allocation)
 * with [PhysicalSize.rounded].
 */
data class PhysicalSize<T>(val width: T, val height: T)

/**
 * A size measured in logical pixels: physical pixels divided by the output's
 * scale factor. As with [PhysicalSize], the compositor uses [Double] for [T]
 * so conversions between physical and logical units never lose precision.
 */
data class LogicalSize<T>(val width: T, val height: T)

/**
 * A size expressed either in physical or logical pixels, mirroring winit's
 * `Size` enum. It does not itself carry a scale factor; the meaning of the
 * contained value is resolved by whichever unit the caller picked.
 */
sealed interface Size<T> {
    val width: T
    val height: T

    /** A size already expressed in physical pixels. */
    data class Physical<T>(val size: PhysicalSize<T>) : Size<T> {
        override val width: T get() = size.width
        override val height: T get() = size.height
    }

    /** A size expressed in logical pixels (scale factor applied elsewhere). */
    data class Logical<T>(val size: LogicalSize<T>) : Size<T> {
        override val width: T get() = size.width
        override val height: T get() = size.height
    }
}

/**
 * An output's scaling factor: logical pixels are multiplied by this to obtain
 * physical pixels. Fractional values (1.25, 1.5, 2.0, ...) are fully
 * supported.
 */
@JvmInline
value class ScaleFactor(val value: Double) {
    companion object {
        val ONE = ScaleFactor(1.0)
    }
}

/**
 * Converts physical pixels to logical pixels by dividing by [scale].
 * Pure floating-point arithmetic: the inverse of [LogicalSize.toPhysical],
 * with no rounding applied.
 */
fun PhysicalSize<Double>.toLogical(scale: ScaleFactor): LogicalSize<Double> =
    LogicalSize(width / scale.value, height / scale.value)

/**
 * Converts logical pixels to physical pixels by multiplying by [scale].
 * Pure floating-point arithmetic: the inverse of [PhysicalSize.toLogical].
 * The result may be fractional; round only when handing pixels to hardware.
 */
fun LogicalSize<Double>.toPhysical(scale: ScaleFactor): PhysicalSize<Double> =
    PhysicalSize(width * scale.value, height * scale.value)

/** Rounds physical pixels to whole pixels for use at a hardware boundary. */
fun PhysicalSize<Double>.rounded(): PhysicalSize<Int> =
    PhysicalSize(round(width).toInt(), round(height).toInt())
