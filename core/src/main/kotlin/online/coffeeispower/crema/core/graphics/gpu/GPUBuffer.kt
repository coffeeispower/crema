package online.coffeeispower.crema.core.graphics.gpu

import online.coffeeispower.crema.core.graphics.ColorMode

interface GPUBuffer : AutoCloseable {
    val width: Int
    val height: Int
    val colorMode: ColorMode
    val owner: GPU
}