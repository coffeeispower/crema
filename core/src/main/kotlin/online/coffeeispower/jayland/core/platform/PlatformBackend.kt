package online.coffeeispower.jayland.core.platform

import online.coffeeispower.jayland.core.input.InputManager
import online.coffeeispower.jayland.core.graphics.presentation.BlitTarget

/**
 * A platform's presentation, input and event loop, created together by a
 * single factory so they can share resources (a DRM device, a GLFW window,
 * the fd reactor backing [eventLoop], ...). The blit target is where pixels
 * go; the input manager is where device events come from; the event loop is
 * the shared reactor both feed into.
 */
data class PlatformBackend(
    val blitTarget: BlitTarget,
    val inputManager: InputManager,
    val eventLoop: EventLoop,
)