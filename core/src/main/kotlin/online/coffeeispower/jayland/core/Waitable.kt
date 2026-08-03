package online.coffeeispower.jayland.core

interface Waitable {
    suspend fun awaitReadable()
}

interface Signal {
    suspend fun awaitSignaled()
}
