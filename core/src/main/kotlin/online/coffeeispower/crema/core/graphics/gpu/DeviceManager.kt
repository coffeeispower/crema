package online.coffeeispower.crema.core.graphics.gpu

interface DeviceManager : AutoCloseable {
    val gpus: List<GPU>
}

