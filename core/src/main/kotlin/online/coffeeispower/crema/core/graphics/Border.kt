package online.coffeeispower.crema.core.graphics

data class Border(
    val borderColor: Color,
    val borderWidth: Float = 1f,
    val borderMode: BorderMode = BorderMode.Outside
) {}