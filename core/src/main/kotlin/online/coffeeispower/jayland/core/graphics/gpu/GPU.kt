package online.coffeeispower.jayland.core.graphics.gpu

interface GPU : AutoCloseable {
    val name: String
    val vram: VRam
}