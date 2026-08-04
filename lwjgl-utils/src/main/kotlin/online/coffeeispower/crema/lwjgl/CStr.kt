package online.coffeeispower.crema.lwjgl

import online.coffeeispower.crema.lwjgl.CStr.Companion.stack
import org.lwjgl.PointerBuffer
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer

/**
 * A borrowed, null-terminated C string view, analogous to Rust's `CStr`.
 *
 * `CStr` does not own its memory. The underlying bytes live in the [MemoryStack]
 * scope it was allocated from and become dangling as soon as that scope is popped.
 * The caller must keep a `CStr` (and any [PointerBuffer] referencing it) within the
 * lifetime of the stack scope it borrows from.
 */
@JvmInline
value class CStr private constructor(val buffer: ByteBuffer) {

    /** Native address of the first character. Safe to pass to C APIs. */
    val pointer: Long
        get() = MemoryUtil.memAddress(buffer)

    /** Number of characters before the null terminator. */
    val length: Int
        get() = buffer.remaining() - 1

    /** Decodes the string as UTF-8. */
    fun str(): String = MemoryUtil.memUTF8(buffer.slice(0, length))

    override fun toString(): String = str()

    companion object {
        /**
         * Encodes [string] onto [stack] as a null-terminated UTF-8 string.
         *
         * The result borrows from [stack] and must not outlive the enclosing stack
         * scope (see [CStr]).
         *
         * @throws IllegalArgumentException if [string] contains an interior NUL
         *   character (U+0000), mirroring Rust's `CString::new` `NulError`.
         */
        fun stack(stack: MemoryStack, string: String): CStr {
            requireNulFree(string)
            return CStr(stack.UTF8(string, true))
        }

        /**
         * Encodes [strings] onto [stack] as a null-terminated array of C strings,
         * for APIs taking a `char**` (e.g. Vulkan layer/extension lists).
         *
         * The returned [PointerBuffer] and its entries borrow from [stack].
         */
        fun array(stack: MemoryStack, strings: List<String>): PointerBuffer {
            val pointers = stack.mallocPointer(strings.size)
            for ((index, string) in strings.withIndex()) {
                requireNulFree(string)
                pointers.put(index, stack.UTF8(string, true))
            }
            return pointers
        }

        /**
         * Views [pointers] as a list of null-terminated C strings (a `char**`),
         * without copying their bytes.
         *
         * Each returned [CStr] borrows from the same memory as the pointed-to
         * strings and must not outlive it.
         */
        fun list(pointers: PointerBuffer): List<CStr> =
            List(pointers.remaining()) { index ->
                val address = pointers.get(index)
                val view = MemoryUtil.memByteBufferNT1(address)
                CStr(MemoryUtil.memByteBuffer(address, view.remaining() + 1))
            }

        /** Decodes the C strings in [pointers] as a [List] of UTF-8 [String]s. */
        fun strings(pointers: PointerBuffer): List<String> = list(pointers).map { it.str() }

        private fun requireNulFree(string: String) {
            require('\u0000' !in string) { "C strings cannot contain interior NUL bytes: \"$string\"" }
        }
    }
}
