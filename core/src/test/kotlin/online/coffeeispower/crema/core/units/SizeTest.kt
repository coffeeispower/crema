package online.coffeeispower.crema.core.units

import kotlin.test.Test
import kotlin.test.assertEquals

class SizeTest {

    private val scale = ScaleFactor(1.5)

    @Test
    fun `toLogical divides by the scale factor`() {
        val logical = PhysicalSize(1920.0, 1080.0).toLogical(scale)
        assertEquals(LogicalSize(1280.0, 720.0), logical)
    }

    @Test
    fun `toPhysical multiplies by the scale factor`() {
        val physical = LogicalSize(1280.0, 720.0).toPhysical(scale)
        assertEquals(PhysicalSize(1920.0, 1080.0), physical)
    }

    @Test
    fun `round trip with a fractional scale factor is lossless`() {
        val physical = PhysicalSize(1920.0, 1080.0)
        assertEquals(physical, physical.toLogical(scale).toPhysical(scale))
    }

    @Test
    fun `round trip is lossless for off-pixel logical coordinates`() {
        val logical = LogicalSize(13.7, 42.25)
        val physical = logical.toPhysical(scale)
        val roundTrip = physical.toLogical(scale)
        assertEquals(logical.width, roundTrip.width, 1e-9)
        assertEquals(logical.height, roundTrip.height, 1e-9)
    }

    @Test
    fun `rounded coerces physical pixels to whole pixels for hardware`() {
        assertEquals(
            PhysicalSize(1921, 1080),
            PhysicalSize(1920.6, 1080.4).rounded(),
        )
    }

    @Test
    fun `scale factor one keeps physical and logical sizes equal`() {
        assertEquals(
            LogicalSize(1920.0, 1080.0),
            PhysicalSize(1920.0, 1080.0).toLogical(ScaleFactor.ONE),
        )
    }

    @Test
    fun `size variants expose width and height`() {
        assertEquals(320, Size.Physical(PhysicalSize(320, 200)).width)
        assertEquals(200, Size.Physical(PhysicalSize(320, 200)).height)
        assertEquals(213.0, Size.Logical(LogicalSize(213.0, 133.0)).width)
        assertEquals(133.0, Size.Logical(LogicalSize(213.0, 133.0)).height)
    }
}
