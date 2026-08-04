package online.coffeeispower.jayland.core.monitors

import online.coffeeispower.jayland.core.graphics.ColorMode

/** Physical specs of the monitor */
data class Monitor(
    val name: String,
    val width: Int,
    val height: Int,
    val refreshRateHz: Int,
    val supportedColorModes: List<ColorMode> = listOf(),
)