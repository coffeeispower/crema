package online.coffeeispower.jayland.core.graphics.gpu

interface DeviceManager : AutoCloseable {
    val gpus: List<GPU>
}

