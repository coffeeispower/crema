package online.coffeeispower.crema.core.graphics

data class Rectangle(val x: Float, val y: Float, val width: Float, val height: Float) {
    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float): Rectangle =
            Rectangle(centerX - width / 2.0f, centerY - height / 2.0f, width, height)
    }
}

data class RoundedRectangle(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
) {
    companion object {
        fun centered(centerX: Float, centerY: Float, width: Float, height: Float, radius: Float): RoundedRectangle =
            RoundedRectangle(centerX - width / 2.0f, centerY - height / 2.0f, width, height, radius)
    }
}
