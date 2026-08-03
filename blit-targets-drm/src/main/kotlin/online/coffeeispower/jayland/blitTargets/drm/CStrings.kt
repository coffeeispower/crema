package online.coffeeispower.jayland.blitTargets.drm

import java.lang.foreign.MemorySegment

/**
 * Reads a NUL-terminated C string from a raw pointer such as the ones FFM
 * downcalls return (which have byteSize 0). The string itself is bounded by
 * its NUL terminator, so a generous reinterpret is safe: [MemorySegment.getString]
 * stops at the terminator within the region.
 */
internal fun MemorySegment.cString(): String = reinterpret(4096).getString(0)
