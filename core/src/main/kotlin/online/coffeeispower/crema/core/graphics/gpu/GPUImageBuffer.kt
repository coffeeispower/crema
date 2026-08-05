package online.coffeeispower.crema.core.graphics.gpu

import online.coffeeispower.crema.core.graphics.ColorMode

interface GPUImageBuffer : GPUBuffer {
    val width: Int
    val height: Int
    val colorMode: ColorMode
}