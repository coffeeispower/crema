package online.coffeeispower.jayland.core.graphics.gpu

import online.coffeeispower.jayland.core.graphics.ColorMode

interface GPUBuffer : AutoCloseable {
    val width: Int
    val height: Int
    val colorMode: ColorMode
    val owner: GPU
}