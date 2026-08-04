package online.coffeeispower.crema.core.input

/**
 * Consumes device input (keyboards, pointers, touch, ...) fed by the platform.
 * A [online.coffeeispower.crema.core.platform.PlatformBackend] factory creates it together with its blit target so both
 * can share the platform's event sources.
 */
interface InputManager : AutoCloseable {
    override fun close() = Unit
}