package online.coffeeispower.jayland.core.platform

import online.coffeeispower.jayland.core.graphics.renderer.Renderer
import online.coffeeispower.jayland.core.graphics.gpu.DeviceManager

/**
 * A candidate backend, described by the [presentationBackend], optional
 * [inputBackend] and [rendererBackend] it combines. The [name] is derived
 * from those in `<presentation>[+<input>]+<renderer>` order, e.g.
 * "drm+libinput+vulkan" or "wayland+vulkan"; the input part is omitted when
 * the presentation backend provides input itself (a windowed blit target).
 *
 * [renderer] builds the GPU-side renderer; [platform] builds the whole
 * platform side (presentation + input + event loop) in one call so the pieces
 * can be wired together, and receives the renderer's [DeviceManager] so it
 * can match its connectors to the available GPUs and allocate scanout buffers
 * on the GPU that drives them.
 */
data class BackendConfig(
    val presentationBackend: String,
    val rendererBackend: String,
    val inputBackend: String? = null,
    val renderer: () -> Renderer,
    val platform: (DeviceManager) -> PlatformBackend,
) {
    /** e.g. "drm+libinput+vulkan" */
    val name: String
        get() = listOfNotNull(presentationBackend, inputBackend, rendererBackend).joinToString("+")
}
