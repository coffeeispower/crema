package online.coffeeispower.crema.blitTargets.drm

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout

/**
 * A libudev context, used to resolve a DRM primary node (`/dev/dri/cardN`)
 * from a (major, minor) device pair. Create a fresh context with
 * [create] and [close][UdevContext.close] it when done; libudev contexts are
 * reference-counted and a singleton that outlives its unref would make later
 * `udev_unref` calls trip libudev's own assertions.
 *
 * Kept deliberately tiny and inline (no jextract task, no extra module): just
 * enough udev to walk `/sys` through the library, the way a compositor is
 * expected to, when matching Vulkan GPUs to DRM cards.
 */
class UdevContext private constructor(private val handle: MemorySegment) : AutoCloseable {

    /** Creates a udev device for a `sysname` (e.g. `card0`) in [subsystem] (e.g. `drm`), or null when absent. */
    fun deviceNewFromSubsystemSysname(subsystem: String, sysname: String): MemorySegment? =
        Arena.ofConfined().use { arena ->
            val sub = arena.allocateFrom(subsystem)
            val name = arena.allocateFrom(sysname)
            val device = udevDeviceNewFromSubsystemSysname.invoke(handle, sub, name) as MemorySegment
            if (device.address() == 0L) null else device
        }

    /** The device node of [device] (e.g. `/dev/dri/card0`), or null. */
    fun deviceGetDevnode(device: MemorySegment): String? =
        (udevDeviceGetDevnode.invoke(device) as MemorySegment).takeIf { it.address() != 0L }?.cString()

    /** The sysname of [device] (e.g. `card0`), or null. */
    fun deviceGetSysname(device: MemorySegment): String? =
        (udevDeviceGetSysname.invoke(device) as MemorySegment).takeIf { it.address() != 0L }?.cString()

    /** A sysfs attribute of [device], e.g. `dev` -> `226:0`, or null when absent. */
    fun deviceGetSysattrValue(device: MemorySegment, attribute: String): String? =
        Arena.ofConfined().use { arena ->
            val attr = arena.allocateFrom(attribute)
            val value = udevDeviceGetSysattrValue.invoke(device, attr) as MemorySegment
            if (value.address() == 0L) null else value.cString()
        }

    fun deviceUnref(device: MemorySegment) {
        udevDeviceUnref.invoke(device)
    }

    override fun close() {
        udevUnref.invoke(handle)
    }

    companion object {
        private val POINTER = ValueLayout.ADDRESS

        private val linker = Linker.nativeLinker()

        /** libudev is a shared library, not part of libc, so load it explicitly. */
        private val lookup: SymbolLookup = loadLibrary("libudev.so.1")
            ?: loadLibrary("libudev.so")
            ?: loadLibrary("libudev")
            ?: linker.defaultLookup().also {
                require(it.find("udev_new").isPresent) {
                    "libudev is required for DRM card resolution but could not be loaded"
                }
            }

        private fun loadLibrary(name: String): SymbolLookup? = try {
            SymbolLookup.libraryLookup(name, Arena.global())
        } catch (e: Throwable) {
            null
        }

        private fun downcall(name: String, returnLayout: ValueLayout, vararg args: ValueLayout) =
            linker.downcallHandle(lookup.findOrThrow(name), FunctionDescriptor.of(returnLayout, *args))

        private val udevNew = downcall("udev_new", POINTER)
        private val udevUnref = downcall("udev_unref", POINTER, POINTER)
        private val udevDeviceNewFromSubsystemSysname =
            downcall("udev_device_new_from_subsystem_sysname", POINTER, POINTER, POINTER, POINTER)
        private val udevDeviceUnref = downcall("udev_device_unref", POINTER, POINTER)
        private val udevDeviceGetDevnode = downcall("udev_device_get_devnode", POINTER, POINTER)
        private val udevDeviceGetSysname = downcall("udev_device_get_sysname", POINTER, POINTER)
        private val udevDeviceGetSysattrValue = downcall("udev_device_get_sysattr_value", POINTER, POINTER, POINTER)

        /** Creates a new libudev context; call [close][UdevContext.close] when done. */
        fun create(): UdevContext {
            val handle = udevNew.invoke() as MemorySegment
            check(handle.address() != 0L) { "udev_new() returned a null context" }
            return UdevContext(handle)
        }
    }
}
