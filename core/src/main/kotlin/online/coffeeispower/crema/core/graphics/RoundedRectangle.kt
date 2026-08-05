package online.coffeeispower.crema.core.graphics

data class RoundedRectangle(
    val rectangle: Rectangle,
    val radius: Float,
) {
    val x: Float
        get() = rectangle.x
    val y: Float
        get() = rectangle.y
    val width: Float
        get() = rectangle.width
    val height: Float
        get() = rectangle.height
    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float, radius: Float): RoundedRectangle =
            RoundedRectangle(Rectangle.centered(centerX, centerY, width, height), radius)
    }
}