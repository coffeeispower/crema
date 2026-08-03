package online.coffeeispower.jayland.core

/**
 * The presentation backend an output blits rendered frames into, e.g. DRM/KMS,
 * a Wayland surface or a native window. It is deliberately presentation-only:
 * input (libinput, win32 messages, ...) is a separate concern composed by the
 * platform factory into a [PlatformBackend]. Implementations are responsible
 * for releasing their presentation resources in [close].
 */
interface BlitTarget : AutoCloseable {
    val connectorManager: ConnectorManager
}
