package online.coffeeispower.crema.core.graphics.gpu

interface GPUBuffer: AutoCloseable {
    val owner: GPU
}