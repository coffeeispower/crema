package online.coffeeispower.crema.core.graphics

data class Color(val r: Float, val g: Float, val b: Float, val a: Float = 1.0f) {
    companion object {
        val RED = Color(1.0f, 0.0f, 0.0f)
    }
}
