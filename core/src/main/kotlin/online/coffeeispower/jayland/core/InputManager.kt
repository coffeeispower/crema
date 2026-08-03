package online.coffeeispower.jayland.core

/**
 * Consumes device input (keyboards, pointers, touch, ...) fed by the platform.
 * A [PlatformBackend] factory creates it together with its blit target so both
 * can share the platform's event sources.
 */
interface InputManager : AutoCloseable {
    override fun close() = Unit
}
